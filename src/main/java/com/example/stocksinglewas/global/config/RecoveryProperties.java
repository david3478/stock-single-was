package com.example.stocksinglewas.global.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 복구설정. application.yaml의 trading.recovery 값을 기동 시 바인딩·검증한다.
 *
 * @param restoreBatchSize 기동 복구 때 대기 주문을 DB에서 한 번에 읽는 건수(EA 복구설정.주문조회건수)
 * @param monitorInterval  체결감시 주기. 상태 불일치는 두 주기 연속 관찰될 때 경고한다(ADR 0005, 0013)
 */
@Validated
@ConfigurationProperties("trading.recovery")
public record RecoveryProperties(
        @Positive int restoreBatchSize,
        @NotNull Duration monitorInterval) {
}
