package com.example.stocksinglewas.order.dto;

import com.example.stocksinglewas.order.entity.Side;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 주문접수요청DTO.
 *
 * @param clientOrderId 요청번호. 클라이언트가 만들며, 같은 값으로 다시 보내면 기존 주문을 돌려준다
 * @param limitPrice    지정가(원)
 */
public record PlaceOrderRequest(
        @NotBlank @Size(max = 100) String clientOrderId,
        @NotBlank @Size(max = 20) String symbolCode,
        @NotNull Side side,
        @Positive long limitPrice,
        @Positive int quantity) {
}
