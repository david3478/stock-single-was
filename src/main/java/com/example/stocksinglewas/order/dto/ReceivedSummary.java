package com.example.stocksinglewas.order.dto;

/**
 * 종목 하나의 처리 전(RECEIVED) 주문 요약. 체결감시가 쓴다.
 *
 * @param count             RECEIVED 주문 수
 * @param oldestAgeSeconds  가장 먼저 접수된 RECEIVED 주문이 기다린 시간(초). 없으면 0
 */
public record ReceivedSummary(long count, double oldestAgeSeconds) {
}
