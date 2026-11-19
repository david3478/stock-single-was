package com.example.stocksinglewas.order.entity;

/** 매매구분(EA: 매매구분). */
public enum Side {
    BUY,
    SELL;

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
