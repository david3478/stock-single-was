package com.example.stocksinglewas.matching.service;

import com.example.stocksinglewas.matching.engine.Fill;

/** 예약과 정산 금액 계산. 모든 연산은 오버플로 시 ArithmeticException을 던진다. */
public final class SettlementCalculator {

    private SettlementCalculator() {
    }

    /** 매수 접수 때 cash_balance에서 reserved_cash로 옮길 금액. */
    public static long buyReservation(long limitPrice, int quantity) {
        return Math.multiplyExact(limitPrice, quantity);
    }

    public static FillSettlement settle(Fill fill) {
        if (fill.price() > fill.buyLimitPrice()) {
            throw new IllegalArgumentException(
                    "fill price " + fill.price() + " above buyer limit " + fill.buyLimitPrice());
        }
        long release = Math.multiplyExact(fill.buyLimitPrice(), fill.quantity());
        long proceeds = Math.multiplyExact(fill.price(), fill.quantity());
        return new FillSettlement(release, Math.subtractExact(release, proceeds), proceeds, fill.quantity());
    }
}
