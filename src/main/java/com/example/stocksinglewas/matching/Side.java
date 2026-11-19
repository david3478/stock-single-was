package com.example.stocksinglewas.matching;

/** 매매구분. */
public enum Side {
    BUY,
    SELL;

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
