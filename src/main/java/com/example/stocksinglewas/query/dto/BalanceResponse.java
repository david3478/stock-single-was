package com.example.stocksinglewas.query.dto;

/**
 * 잔고조회DTO. 총현금 = 사용 가능 현금 + 예약 현금.
 *
 * @param availableCash 사용 가능 현금(원)
 * @param reservedCash  접수된 매수 주문이 묶어 둔 현금(원)
 */
public record BalanceResponse(long availableCash, long reservedCash) {
}
