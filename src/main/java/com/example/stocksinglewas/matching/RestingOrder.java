package com.example.stocksinglewas.matching;

import com.example.stocksinglewas.entity.Side;

import java.util.Objects;

/**
 * 대기주문목록에 있는 주문. 가격과 최초 처리순번(seq)으로 우선순위가 정해지며,
 * 부분 체결되어도 seq는 바뀌지 않는다(ADR 0001).
 */
public record RestingOrder(long orderId, long accountId, Side side, long limitPrice, int remaining, long seq) {

    public RestingOrder {
        Objects.requireNonNull(side, "side");
        if (limitPrice <= 0) {
            throw new IllegalArgumentException("limitPrice must be positive: " + limitPrice);
        }
        if (remaining <= 0) {
            throw new IllegalArgumentException("remaining must be positive: " + remaining);
        }
        if (seq <= 0) {
            throw new IllegalArgumentException("seq must be positive: " + seq);
        }
    }

    RestingOrder withRemaining(int newRemaining) {
        return new RestingOrder(orderId, accountId, side, limitPrice, newRemaining, seq);
    }
}
