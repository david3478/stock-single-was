package com.example.stocksinglewas.notification.service;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 연결 하나. 같은 계좌로 여러 탭이 연결하면 연결마다 하나씩 생긴다.
 *
 * <p>세 표시로 한 연결에는 한 번에 한 스레드만 보내게 한다.
 * <ul>
 *   <li>{@code scheduled}: 이 연결의 송신 작업이 실행기에 올라가 있거나 실행 중이다. 잡은 스레드만 보낸다.</li>
 *   <li>{@code dirty}: 마지막으로 DB를 읽은 뒤 새 알림이 생겼을 수 있다. 다시 읽어야 한다.</li>
 *   <li>{@code heartbeatDue}: 연결유지 ping을 보내야 한다.</li>
 * </ul>
 * {@code lastEventId}는 {@code scheduled}를 잡은 스레드만 읽고 쓰므로 따로 잠그지 않는다.
 * 잡고 놓는 것이 AtomicBoolean이라 다음에 잡는 스레드에게 값이 보인다.
 */
final class SseSubscriber {

    final long id;
    final long accountId;
    final SseEmitter emitter;
    final AtomicBoolean scheduled = new AtomicBoolean();
    final AtomicBoolean dirty = new AtomicBoolean();
    final AtomicBoolean heartbeatDue = new AtomicBoolean();
    volatile boolean closed;
    long lastEventId;
    /** 구독 직후 밀린 알림을 다 보냈다는 표시(:replayed)를 아직 보내지 않았다. lastEventId처럼 scheduled를 잡은 스레드만 쓴다. */
    boolean replayMarkerPending = true;

    SseSubscriber(long id, long accountId, SseEmitter emitter, long lastEventId) {
        this.id = id;
        this.accountId = accountId;
        this.emitter = emitter;
        this.lastEventId = lastEventId;
    }

    @Override
    public String toString() {
        return "subscriber " + id + " (account " + accountId + ")";
    }
}
