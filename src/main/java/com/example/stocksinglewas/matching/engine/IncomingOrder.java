package com.example.stocksinglewas.matching.engine;

import com.example.stocksinglewas.order.entity.Side;

import java.util.Objects;

/** 워커가 처음 처리하는 RECEIVED 주문. 아직 처리순번이 없다. */
public record IncomingOrder(long orderId, long accountId, Side side, long limitPrice, int quantity) {

    public IncomingOrder {
        Objects.requireNonNull(side, "side");
        if (limitPrice <= 0) {
            throw new IllegalArgumentException("limitPrice must be positive: " + limitPrice);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
    }
}
