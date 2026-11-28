package com.example.stocksinglewas.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.stocksinglewas.account.dao.AccountDao;
import com.example.stocksinglewas.global.config.NotificationProperties;
import com.example.stocksinglewas.global.exception.NotFoundException;
import com.example.stocksinglewas.notification.dao.NotificationDao;
import com.example.stocksinglewas.notification.dto.NotificationView;
import com.example.stocksinglewas.query.dto.BalanceResponse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 알림전송Service(NotificationPushService). 신호를 받으면 DB에서 마지막으로 보낸 번호 이후를 읽어 보낸다.
 * 연결별 송신 직렬화, 신호 합치기, 대기열 거절 처리가 결과를 정하므로 DB 없이
 * 제출된 작업을 모아 두는 실행기와 전송 내용을 기록하는 연결로 순서를 직접 만든다.
 */
class NotificationPushServiceTest {

    static final long ACCOUNT = 1;
    static final NotificationProperties PROPS = new NotificationProperties(
            2, 100, Duration.ofSeconds(15), Duration.ofMinutes(30), Duration.ofSeconds(3), 2);

    final List<NotificationView> store = Collections.synchronizedList(new ArrayList<>());
    final List<RecordingEmitter> emitters = Collections.synchronizedList(new ArrayList<>());
    NotificationDao dao;
    AccountDao accounts;
    ManualExecutor executor;
    SimpleMeterRegistry meters;
    SseEmitterRegistry registry;
    NotificationPushService service;

    @BeforeEach
    void setUp() {
        dao = mock(NotificationDao.class);
        when(dao.findAfter(eq(ACCOUNT), anyLong(), anyInt())).thenAnswer(inv -> {
            long after = inv.getArgument(1);
            int limit = inv.getArgument(2);
            synchronized (store) {
                return store.stream().filter(n -> n.id() > after).limit(limit).toList();
            }
        });
        when(dao.latestId(ACCOUNT)).thenAnswer(inv -> {
            synchronized (store) {
                return store.stream().mapToLong(NotificationView::id).max().orElse(0);
            }
        });
        accounts = mock(AccountDao.class);
        when(accounts.findBalance(ACCOUNT)).thenReturn(Optional.of(new BalanceResponse(0, 0)));
        executor = new ManualExecutor();
        meters = new SimpleMeterRegistry();
        registry = new SseEmitterRegistry(() -> {
            RecordingEmitter e = new RecordingEmitter();
            emitters.add(e);
            return e;
        }, PROPS.reconnectTime());
        service = new NotificationPushService(registry, dao, accounts, executor, PROPS, meters);
    }

    void stored(long... ids) {
        for (long id : ids) {
            store.add(new NotificationView(id, "ORDER_OPEN", "{\"orderId\":" + id + "}"));
        }
    }

    RecordingEmitter emitter() {
        return emitters.getLast();
    }

    // --- 구독 ---

    @Test
    void 마지막_번호를_주고_구독하면_그_다음_알림부터_보낸다() {
        stored(1, 2, 3, 4, 5);

        service.subscribe(ACCOUNT, 2L);
        executor.runAll();

        // 한 번에 2건씩 읽어도 끝까지 이어서 보낸다
        assertThat(emitter().sentIds()).containsExactly(3L, 4L, 5L);
    }

    @Test
    void 마지막_번호_없이_구독하면_구독_이후의_알림만_보낸다() {
        stored(1, 2);

        service.subscribe(ACCOUNT, null);
        executor.runAll();
        stored(3);
        service.signal(List.of(ACCOUNT));
        executor.runAll();

        assertThat(emitter().sentIds()).containsExactly(3L);
    }

    @Test
    void 구독하면_재연결_대기시간을_먼저_알려준다() {
        service.subscribe(ACCOUNT, null);

        assertThat(emitter().raw.getFirst()).contains("retry:3000");
    }

    // --- 밀린 알림 전송 완료 표시(:replayed, QAS 5 단절 구간 판정용) ---

    @Test
    void 구독하면_밀린_알림을_다_보낸_뒤_마지막으로_보낸_번호를_한_번_표시한다() {
        stored(1, 2, 3, 4, 5);

        service.subscribe(ACCOUNT, 2L);
        executor.runAll();
        stored(6);
        service.signal(List.of(ACCOUNT));
        executor.runAll();

        assertThat(emitter().sentIds()).containsExactly(3L, 4L, 5L, 6L);
        assertThat(emitter().comments()).containsExactly("connected", "replayed 5");
        assertThat(emitter().indexOfText(":replayed 5")).as("밀린 알림 뒤, 새 알림 앞")
                .isGreaterThan(emitter().indexOfId(5)).isLessThan(emitter().indexOfId(6));
    }

    @Test
    void 밀린_알림이_없으면_시작_번호를_표시한다() {
        stored(1, 2);

        service.subscribe(ACCOUNT, null);
        executor.runAll();

        assertThat(emitter().sentIds()).isEmpty();
        assertThat(emitter().comments()).containsExactly("connected", "replayed 2");
    }

    @Test
    void 밀린_알림을_읽다가_실패하면_표시하지_않고_다음에_다_보낸_뒤_표시한다() {
        stored(1, 2, 3);
        when(dao.findAfter(eq(ACCOUNT), anyLong(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenAnswer(inv -> store.stream().filter(n -> n.id() > (long) inv.getArgument(1)).toList());

        service.subscribe(ACCOUNT, 0L);
        executor.runAll();
        assertThat(emitter().comments()).containsExactly("connected");

        service.signal(List.of(ACCOUNT));
        executor.runAll();

        assertThat(emitter().sentIds()).containsExactly(1L, 2L, 3L);
        assertThat(emitter().comments()).containsExactly("connected", "replayed 3");
    }

    @Test
    void 없는_계좌로_구독하면_거절한다() {
        when(accounts.findBalance(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.subscribe(99, null)).isInstanceOf(NotFoundException.class);
        assertThat(emitters).isEmpty();
    }

    // --- 전송예약: 연결별 직렬화와 신호 합치기 ---

    @Test
    void 보내기_전에_신호가_여러_번_와도_작업은_하나만_제출하고_한_번씩만_보낸다() {
        service.subscribe(ACCOUNT, 0L);
        executor.runAll();
        stored(1, 2);

        service.signal(List.of(ACCOUNT));
        service.signal(List.of(ACCOUNT));
        service.signal(List.of(ACCOUNT));

        assertThat(executor.pending()).isEqualTo(1);
        executor.runAll();
        assertThat(emitter().sentIds()).containsExactly(1L, 2L);
    }

    @Test
    void 여러_스레드가_동시에_신호를_보내도_한_연결에는_작업이_하나만_제출된다() throws Exception {
        service.subscribe(ACCOUNT, 0L);
        executor.runAll();
        // 경쟁은 scheduled가 false → true로 바뀌는 순간에만 생긴다. 라운드마다 작업을 비워 그 순간을 여러 번 만든다.
        int threads = 8;
        int rounds = 2_000;
        CyclicBarrier start = new CyclicBarrier(threads + 1);
        CyclicBarrier end = new CyclicBarrier(threads + 1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                for (int r = 0; r < rounds; r++) {
                    start.await();
                    service.signal(List.of(ACCOUNT));
                    end.await();
                }
                return null;
            }));
        }
        int maxPending = 0;
        for (int r = 0; r < rounds; r++) {
            start.await(10, TimeUnit.SECONDS);
            end.await(10, TimeUnit.SECONDS);
            maxPending = Math.max(maxPending, executor.pending());
            executor.runAll();
        }
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(maxPending).isEqualTo(1);
    }

    @Test
    void 보내는_중에_온_신호는_끝난_뒤_이어서_보낸다() throws Exception {
        service.subscribe(ACCOUNT, 0L);
        executor.runAll();
        stored(1);
        service.signal(List.of(ACCOUNT));
        emitter().blockOnId(1);

        Thread sender = Thread.ofPlatform().start(executor::runAll);
        emitter().awaitBlocked();
        stored(2);
        service.signal(List.of(ACCOUNT));
        assertThat(executor.pending()).as("보내는 중이면 새 작업을 제출하지 않는다").isZero();
        emitter().release();
        sender.join(5_000);

        assertThat(emitter().sentIds()).containsExactly(1L, 2L);
    }

    @Test
    void 전송_표시를_내려놓는_순간_들어온_신호도_놓치지_않는다() throws Exception {
        service.subscribe(ACCOUNT, 0L);
        executor.runAll();
        // 연결유지 ping은 미전송분을 다 보낸 뒤, 전송 표시(scheduled)를 내려놓기 직전에 보낸다.
        // ping 전송에서 멈춰 "다 보냈다고 판단한 뒤 ~ 내려놓기 전" 순간에 신호를 끼워 넣는다.
        emitter().blockOnPing();
        service.heartbeat();
        Thread sender = Thread.ofPlatform().start(executor::runAll);
        emitter().awaitBlocked();

        stored(1);
        service.signal(List.of(ACCOUNT));
        emitter().release();
        sender.join(5_000);
        executor.runAll();

        assertThat(emitter().sentIds()).containsExactly(1L);
    }

    // --- 대기열 거절 ---

    @Test
    void 대기열이_가득_차_거절되면_전송_표시를_되돌려_다음_신호에_보낸다() {
        service.subscribe(ACCOUNT, 0L);
        executor.runAll();
        stored(1);
        executor.rejectNext(1);

        service.signal(List.of(ACCOUNT));
        assertThat(executor.pending()).isZero();
        stored(2);
        service.signal(List.of(ACCOUNT));
        executor.runAll();

        assertThat(emitter().sentIds()).containsExactly(1L, 2L);
        assertThat(meters.counter("notification.push.rejected").count()).isEqualTo(1);
    }

    @Test
    void 거절로_잃은_신호는_다음_연결유지_때_보낸다() {
        service.subscribe(ACCOUNT, 0L);
        executor.runAll();
        stored(1);
        executor.rejectNext(1);
        service.signal(List.of(ACCOUNT));

        service.heartbeat();
        executor.runAll();

        assertThat(emitter().sentIds()).containsExactly(1L);
        assertThat(emitter().pings()).isEqualTo(1);
    }

    @Test
    void 잃은_신호가_없으면_연결유지는_DB를_읽지_않고_ping만_보낸다() {
        service.subscribe(ACCOUNT, 0L);
        executor.runAll();
        org.mockito.Mockito.clearInvocations(dao);

        service.heartbeat();
        executor.runAll();

        verify(dao, never()).findAfter(anyLong(), anyLong(), anyInt());
        assertThat(emitter().pings()).isEqualTo(1);
    }

    // --- 실패 ---

    @Test
    void 전송에_실패한_연결은_정리하고_더_보내지_않는다() {
        service.subscribe(ACCOUNT, 0L);
        executor.runAll();
        stored(1);
        emitter().failSends();

        service.signal(List.of(ACCOUNT));
        executor.runAll();
        service.signal(List.of(ACCOUNT));

        assertThat(registry.subscribers(ACCOUNT)).isEmpty();
        assertThat(executor.pending()).isZero();
    }

    @Test
    void DB_조회가_실패해도_전송_표시를_되돌려_다음_신호에_다시_시도한다() {
        service.subscribe(ACCOUNT, 0L);
        executor.runAll();
        stored(1);
        when(dao.findAfter(eq(ACCOUNT), anyLong(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenAnswer(inv -> store.stream().filter(n -> n.id() > (long) inv.getArgument(1)).toList());

        service.signal(List.of(ACCOUNT));
        executor.runAll();
        service.signal(List.of(ACCOUNT));
        executor.runAll();

        assertThat(emitter().sentIds()).containsExactly(1L);
    }

    @Test
    void 연결이_없는_계좌의_신호는_아무것도_제출하지_않는다() {
        service.signal(List.of(ACCOUNT, 2L));

        assertThat(executor.pending()).isZero();
    }

    /** 제출된 작업을 모아 두고 테스트가 원할 때 실행한다. 지정한 횟수만큼 제출을 거절할 수 있다. */
    static final class ManualExecutor implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        private int rejections;

        @Override
        public synchronized void execute(Runnable task) {
            if (rejections > 0) {
                rejections--;
                throw new RejectedExecutionException("queue full");
            }
            tasks.add(task);
        }

        synchronized void rejectNext(int count) {
            rejections = count;
        }

        synchronized int pending() {
            return tasks.size();
        }

        void runAll() {
            Runnable task;
            while ((task = poll()) != null) {
                task.run();
            }
        }

        private synchronized Runnable poll() {
            return tasks.poll();
        }
    }

    /** 보낸 이벤트를 기록한다. 실패시키거나, 특정 전송에서 멈출 수 있다. */
    static final class RecordingEmitter extends SseEmitter {
        private static final Pattern ID = Pattern.compile("(?m)^id:(\\d+)$");

        final List<String> raw = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean fail;
        private volatile String blockOn;
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            String text = builder.build().stream()
                    .map(d -> String.valueOf(d.getData()))
                    .collect(Collectors.joining());
            if (fail) {
                throw new IOException("broken pipe");
            }
            String marker = blockOn;
            if (marker != null && text.contains(marker)) {
                blockOn = null;
                blocked.countDown();
                try {
                    released.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            raw.add(text);
        }

        List<Long> sentIds() {
            List<Long> ids = new ArrayList<>();
            synchronized (raw) {
                for (String text : raw) {
                    Matcher m = ID.matcher(text);
                    while (m.find()) {
                        ids.add(Long.parseLong(m.group(1)));
                    }
                }
            }
            return ids;
        }

        /** 주석 줄(":ping" 제외)의 내용. */
        List<String> comments() {
            List<String> result = new ArrayList<>();
            synchronized (raw) {
                for (String text : raw) {
                    for (String line : text.split("\n")) {
                        if (line.startsWith(":") && !line.equals(":ping")) {
                            result.add(line.substring(1));
                        }
                    }
                }
            }
            return result;
        }

        /** 그 글자를 담은 이벤트가 raw에서 몇 번째인지. */
        int indexOfText(String text) {
            synchronized (raw) {
                for (int i = 0; i < raw.size(); i++) {
                    if (raw.get(i).contains(text)) {
                        return i;
                    }
                }
            }
            return -1;
        }

        /** 그 알림 번호를 보낸 이벤트가 raw에서 몇 번째인지. */
        int indexOfId(long id) {
            synchronized (raw) {
                for (int i = 0; i < raw.size(); i++) {
                    if (raw.get(i).contains("id:" + id + "\n")) {
                        return i;
                    }
                }
            }
            return -1;
        }

        long pings() {
            synchronized (raw) {
                return raw.stream().filter(t -> t.contains(":ping")).count();
            }
        }

        void failSends() {
            fail = true;
        }

        void blockOnId(long id) {
            blockOn = "id:" + id + "\n";
        }

        void blockOnPing() {
            blockOn = ":ping";
        }

        void awaitBlocked() throws InterruptedException {
            assertThat(blocked.await(5, TimeUnit.SECONDS)).as("전송이 멈춘 지점에 도달").isTrue();
        }

        void release() {
            released.countDown();
        }
    }
}
