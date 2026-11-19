package com.example.stocksinglewas.dto;

/**
 * 주문접수응답DTO. 접수 확정만 뜻한다. 체결 여부는 이후 조회나 알림으로 확인한다.
 *
 * @param orderId 주문번호
 */
public record PlaceOrderResponse(long orderId) {
}
