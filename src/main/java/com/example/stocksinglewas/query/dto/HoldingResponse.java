package com.example.stocksinglewas.query.dto;

/**
 * 보유종목DTO.
 *
 * @param quantity         총보유량
 * @param reservedQuantity 접수된 매도 주문이 묶어 둔 수량
 * @param sellableQuantity 매도 가능 수량 = 총보유량 - 예약 수량
 */
public record HoldingResponse(String symbolCode, int quantity, int reservedQuantity, int sellableQuantity) {
}
