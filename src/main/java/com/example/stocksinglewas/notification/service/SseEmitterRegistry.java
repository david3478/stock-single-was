package com.example.stocksinglewas.notification.service;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.stocksinglewas.global.config.NotificationProperties;
import com.example.stocksinglewas.notification.dto.NotificationView;

/**
 * SSE연결관리. 계좌별 연결 목록을 들고, 연결 하나에 이벤트 하나를 쓴다.
 * 언제 누가 보낼지는 알림전송Service가 정한다. 이 클래스의 send는 호출자가 직렬화한다고 가정한다.
 *
 * <p>한계: 연결 목록이 프로세스 메모리에 있으므로 WAS 한 대에서만 동작한다.
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    private final Supplier<SseEmitter> emitterFactory;
    private final Duration reconnectTime;
    private final Map<Long, Set<SseSubscriber>> byAccount = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @Autowired
    public SseEmitterRegistry(NotificationProperties props) {
        this(() -> new SseEmitter(props.emitterTimeout().toMillis()), props.reconnectTime());
    }

    /** 테스트에서 전송 내용을 기록하는 연결로 바꿔 끼우기 위한 생성자. */
    SseEmitterRegistry(Supplier<SseEmitter> emitterFactory, Duration reconnectTime) {
        this.emitterFactory = emitterFactory;
        this.reconnectTime = reconnectTime;
    }

    /**
     * 연결등록. 목록에 넣기 전에 재연결 대기시간을 먼저 쓴다.
     * 아직 다른 스레드가 이 연결을 볼 수 없으므로 이 전송은 송신 스레드와 겹치지 않는다.
     */
    SseSubscriber register(long accountId, long lastEventId) {
        SseEmitter emitter = emitterFactory.get();
        SseSubscriber subscriber = new SseSubscriber(ids.incrementAndGet(), accountId, emitter, lastEventId);
        try {
            emitter.send(SseEmitter.event().reconnectTime(reconnectTime.toMillis()).comment("connected"));
        } catch (IOException e) {
            emitter.completeWithError(e);
            throw new IllegalStateException("failed to open SSE stream for account " + accountId, e);
        }
        emitter.onCompletion(() -> remove(subscriber));
        emitter.onTimeout(() -> remove(subscriber));
        emitter.onError(e -> remove(subscriber));
        byAccount.computeIfAbsent(accountId, id -> ConcurrentHashMap.newKeySet()).add(subscriber);
        return subscriber;
    }

    /** 계좌의 연결 목록. 순회 중 연결이 추가·제거되어도 안전하다. */
    List<SseSubscriber> subscribers(long accountId) {
        Set<SseSubscriber> set = byAccount.get(accountId);
        return set == null ? List.of() : List.copyOf(set);
    }

    List<SseSubscriber> all() {
        return byAccount.values().stream().flatMap(Set::stream).toList();
    }

    /** 이벤트전송. 알림 번호가 이벤트 id, 알림종류가 이벤트 이름이다. */
    void send(SseSubscriber subscriber, NotificationView notification) throws IOException {
        subscriber.emitter.send(SseEmitter.event()
                .id(Long.toString(notification.id()))
                .name(notification.type())
                .data(notification.payload(), MediaType.APPLICATION_JSON));
    }

    /** 연결유지 신호. 브라우저는 주석 줄을 무시한다. */
    void sendHeartbeat(SseSubscriber subscriber) throws IOException {
        subscriber.emitter.send(SseEmitter.event().comment("ping"));
    }

    /** 연결제거. 목록에서 빼고 응답을 닫는다. */
    void close(SseSubscriber subscriber) {
        remove(subscriber);
        try {
            subscriber.emitter.complete();
        } catch (RuntimeException e) {
            log.debug("ignored error while closing {}", subscriber, e);
        }
    }

    private void remove(SseSubscriber subscriber) {
        subscriber.closed = true;
        byAccount.computeIfPresent(subscriber.accountId, (id, set) -> {
            set.remove(subscriber);
            return set.isEmpty() ? null : set;
        });
    }
}
