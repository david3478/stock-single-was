package com.example.stocksinglewas.query.dto;

/**
 * 호가 한 단계.
 *
 * @param quantity   이 가격에 대기 중인 잔량 합계
 * @param orderCount 이 가격에 대기 중인 주문 수
 */
public record PriceLevel(long price, long quantity, int orderCount) {
}
