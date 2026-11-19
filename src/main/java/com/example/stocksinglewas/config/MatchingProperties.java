package com.example.stocksinglewas.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 체결설정. application.yaml의 trading.matching 값을 기동 시 바인딩·검증한다.
 *
 * @param queueCapacity       종목별 입력 대기열 용량
 * @param drainBatchSize      대기열 넘침 중 DB에서 RECEIVED를 한 번에 읽는 건수
 * @param pollTimeout         워커가 대기열을 기다리는 시간. 넘침 확인 주기이기도 하다
 * @param deadlockMaxRetries  데드락 시 T2 전체를 다시 시도하는 횟수(최초 실행 제외)
 * @param deadlockRetryDelay  재시도 사이 간격
 * @param autoStart           기동 시 활성 종목의 엔진을 자동으로 준비할지
 */
@Validated
@ConfigurationProperties("trading.matching")
public record MatchingProperties(
        @Positive int queueCapacity,
        @Positive int drainBatchSize,
        @NotNull Duration pollTimeout,
        @Min(0) int deadlockMaxRetries,
        @NotNull Duration deadlockRetryDelay,
        boolean autoStart) {
}
