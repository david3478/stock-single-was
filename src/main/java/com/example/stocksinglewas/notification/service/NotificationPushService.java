package com.example.stocksinglewas.notification.service;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.stocksinglewas.account.dao.AccountDao;
import com.example.stocksinglewas.global.config.NotificationConfig;
import com.example.stocksinglewas.global.config.NotificationProperties;
import com.example.stocksinglewas.global.exception.NotFoundException;
import com.example.stocksinglewas.notification.dao.NotificationDao;
import com.example.stocksinglewas.notification.dto.NotificationView;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 알림전송Service. "신호 + DB에서 읽어 보내기" 구조다.
 *
 * <p>체결 워커는 커밋 후 {@link #signal}로 "이 계좌에 새 알림이 있다"는 신호만 주고 바로 돌아간다.
 * 송신 스레드가 DB에서 {@code id > 마지막으로 보낸 번호}를 읽어 번호 순서대로 보낸다.
 * 신호가 늦거나, 겹치거나, 순서가 바뀌어도 보내는 순서는 항상 DB 번호 순서다.
 *
 * <p>한 연결에는 한 번에 한 스레드만 보낸다({@link SseSubscriber}). 신호는 먼저 {@code dirty}로 남기고
 * {@code scheduled}를 원자적으로 잡은 쪽만 작업을 제출한다. 보내던 스레드는 {@code scheduled}를 놓은 직후
 * 남은 신호를 한 번 더 확인한다. 그래서 연결마다 대기 작업은 최대 1개이고, 놓는 순간 온 신호도 잃지 않는다.
 *
 * <p>송신 대기열이 가득 차 제출이 거절되면 {@code scheduled}를 되돌리고 {@code dirty}는 남긴다.
 * 남은 {@code dirty}는 다음 신호나 연결유지 때 처리된다. 신호를 잃는 경로는 거절과 DB 오류뿐이므로
 * 연결유지는 {@code dirty}가 남은 연결만 DB를 읽고, 나머지는 ping만 보낸다.
 */
@Service
public class NotificationPushService {

    private static final Logger log = LoggerFactory.getLogger(NotificationPushService.class);
    private static final long REJECTION_LOG_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    private final SseEmitterRegistry registry;
    private final NotificationDao notifications;
    private final AccountDao accounts;
    private final Executor sender;
    private final NotificationProperties props;
    private final Counter rejected;
    private final AtomicLong rejectedSinceLog = new AtomicLong();
    private final AtomicLong lastRejectionLog = new AtomicLong(System.nanoTime() - REJECTION_LOG_INTERVAL_NANOS);

    public NotificationPushService(SseEmitterRegistry registry, NotificationDao notifications, AccountDao accounts,
                                   @Qualifier(NotificationConfig.SSE_SENDER_EXECUTOR) Executor sender,
                                   NotificationProperties props, MeterRegistry meters) {
        this.registry = registry;
        this.notifications = notifications;
        this.accounts = accounts;
        this.sender = sender;
        this.props = props;
        this.rejected = Counter.builder("notification.push.rejected")
                .description("signals rejected because the SSE sender queue was full")
                .register(meters);
    }

    /**
     * 구독. 연결을 먼저 등록하고 그다음 DB를 읽는다. 순서가 반대면 읽은 뒤 등록 전에 커밋된 알림의 신호를
     * 아무도 받지 못한다. 마지막 번호가 없으면 지금까지의 최신 번호부터 시작한다(구독 이후 알림만).
     */
    public SseEmitter subscribe(long accountId, Long lastEventId) {
        if (accounts.findBalance(accountId).isEmpty()) {
            throw NotFoundException.account(accountId);
        }
        long start = lastEventId != null ? lastEventId : notifications.latestId(accountId);
        SseSubscriber subscriber = registry.register(accountId, start);
        subscriber.dirty.set(true);
        trySchedule(subscriber);
        return subscriber.emitter;
    }

    /** 전송예약. T2 커밋 후 체결 워커가 부른다. 호출 스레드에서는 DB 조회도 전송도 하지 않는다. */
    public void signal(Collection<Long> accountIds) {
        for (long accountId : accountIds) {
            for (SseSubscriber subscriber : registry.subscribers(accountId)) {
                subscriber.dirty.set(true);
                trySchedule(subscriber);
            }
        }
    }

    /** 연결유지. 프록시가 조용한 연결을 끊지 않게 하고, 끊긴 연결을 찾아내고, 잃은 신호를 처리한다. */
    @Scheduled(fixedRateString = "${trading.notification.heartbeat-interval}")
    public void heartbeat() {
        for (SseSubscriber subscriber : registry.all()) {
            subscriber.heartbeatDue.set(true);
            trySchedule(subscriber);
        }
    }

    private void trySchedule(SseSubscriber subscriber) {
        if (subscriber.closed || !subscriber.scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            sender.execute(() -> drain(subscriber));
        } catch (RejectedExecutionException e) {
            // 되돌리지 않으면 이 연결은 다시는 작업을 제출하지 못한다. dirty는 남겨 다음 기회에 처리한다.
            subscriber.scheduled.set(false);
            onRejected(subscriber);
        }
    }

    /** 송신 스레드에서 실행된다. scheduled를 쥔 동안 이 연결에 보내는 스레드는 이것 하나뿐이다. */
    private void drain(SseSubscriber subscriber) {
        try {
            while (true) {
                while (subscriber.dirty.getAndSet(false)) {
                    sendMissed(subscriber);
                    if (subscriber.replayMarkerPending) {
                        // 구독 시점에 커밋된 밀린 알림을 다 보냈다. 같은 계좌의 알림 번호는 커밋 순서와 같으므로(ADR 0003)
                        // 이 번호 이하의 알림은 모두 이미 보냈다. 클라이언트가 단절 구간을 시계 없이 정할 수 있다(QAS 5)
                        registry.sendReplayed(subscriber, subscriber.lastEventId);
                        subscriber.replayMarkerPending = false;
                    }
                }
                if (subscriber.heartbeatDue.getAndSet(false)) {
                    registry.sendHeartbeat(subscriber);
                }
                subscriber.scheduled.set(false);
                // 놓기 직전에 들어온 신호는 scheduled를 잡지 못하고 돌아갔으므로 여기서 다시 잡는다
                boolean pending = subscriber.dirty.get() || subscriber.heartbeatDue.get();
                if (!pending || subscriber.closed || !subscriber.scheduled.compareAndSet(false, true)) {
                    return;
                }
            }
        } catch (IOException | IllegalStateException e) {
            // 브라우저가 떠났거나 응답이 이미 닫혔다. 재연결하면 Last-Event-ID로 이어서 받는다.
            subscriber.scheduled.set(false);
            registry.close(subscriber);
        } catch (RuntimeException e) {
            subscriber.dirty.set(true);
            subscriber.scheduled.set(false);
            log.warn("failed to push notifications to {}; will retry on next signal or heartbeat", subscriber, e);
        }
    }

    private void sendMissed(SseSubscriber subscriber) throws IOException {
        List<NotificationView> page;
        do {
            page = notifications.findAfter(subscriber.accountId, subscriber.lastEventId, props.replayBatchSize());
            for (NotificationView n : page) {
                registry.send(subscriber, n);
                subscriber.lastEventId = n.id();
            }
        } while (page.size() == props.replayBatchSize());
    }

    /** 대기열이 가득 찬 때는 가장 바쁠 때이므로 로그는 일정 간격으로 묶어서 남기고 횟수는 메트릭으로 센다. */
    private void onRejected(SseSubscriber subscriber) {
        rejected.increment();
        rejectedSinceLog.incrementAndGet();
        long now = System.nanoTime();
        long last = lastRejectionLog.get();
        if (now - last >= REJECTION_LOG_INTERVAL_NANOS && lastRejectionLog.compareAndSet(last, now)) {
            log.warn("SSE sender queue full: {} signals rejected since last report (latest {}); "
                    + "they will be retried on heartbeat", rejectedSinceLog.getAndSet(0), subscriber);
        }
    }
}
