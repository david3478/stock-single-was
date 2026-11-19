package com.example.stocksinglewas.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * 조회설정. application.yaml의 trading.query 값을 기동 시 바인딩·검증한다.
 *
 * @param defaultPageSize       주문 내역 조회 건수를 정하지 않았을 때
 * @param maxPageSize           주문 내역 조회 건수 상한
 * @param defaultOrderBookDepth 호가창 깊이를 정하지 않았을 때 보여줄 가격 단계 수
 * @param maxOrderBookDepth     호가창 깊이 상한
 */
@Validated
@ConfigurationProperties("trading.query")
public record QueryProperties(
        @Positive int defaultPageSize,
        @Positive int maxPageSize,
        @Positive int defaultOrderBookDepth,
        @Positive int maxOrderBookDepth) {
}
