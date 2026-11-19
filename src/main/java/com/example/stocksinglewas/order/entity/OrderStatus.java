package com.example.stocksinglewas.order.entity;

/** 주문상태(EA: 주문상태). DB orders.status의 CHECK 조건과 같다. */
public enum OrderStatus {
    /** 접수 확정, 종목 워커가 아직 처리하지 않음. seq 없음. */
    RECEIVED,
    /** 처리했으나 상대가 없어 전량 대기. */
    OPEN,
    /** 일부 체결, 잔량 대기. */
    PARTIALLY_FILLED,
    /** 전량 체결. */
    FILLED
}
