package com.example.stocksinglewas.query.dto;

import java.time.Instant;

/**
 * 주문내역DTO.
 *
 * @param status RECEIVED(접수, 체결 처리 전) / OPEN(대기) / PARTIALLY_FILLED / FILLED
 */
public record OrderHistoryResponse(
        long orderId,
        String symbolCode,
        String side,
        long limitPrice,
        int quantity,
        int remaining,
        String status,
        Instant createdAt) {
}
