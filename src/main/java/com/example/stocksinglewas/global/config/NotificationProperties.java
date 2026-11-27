package com.example.stocksinglewas.global.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 알림설정. application.yaml의 trading.notification 값을 기동 시 바인딩·검증한다.
 *
 * @param senderThreads       송신 스레드 수
 * @param senderQueueCapacity 송신 대기열 용량. 연결마다 대기 작업은 최대 1개다
 * @param heartbeatInterval   연결유지 신호 간격. 프록시의 유휴 연결 종료 시간보다 짧아야 한다
 * @param emitterTimeout      연결 제한 시간. 지나면 서버가 연결을 닫고 브라우저가 재연결한다
 * @param reconnectTime       끊겼을 때 브라우저가 재연결을 기다리는 시간(SSE retry)
 * @param replayBatchSize     놓친 알림을 DB에서 한 번에 읽는 건수
 */
@Validated
@ConfigurationProperties("trading.notification")
public record NotificationProperties(
        @Positive int senderThreads,
        @Positive int senderQueueCapacity,
        @NotNull Duration heartbeatInterval,
        @NotNull Duration emitterTimeout,
        @NotNull Duration reconnectTime,
        @Positive int replayBatchSize) {
}
