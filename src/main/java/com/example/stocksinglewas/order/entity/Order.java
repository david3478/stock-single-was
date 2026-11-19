package com.example.stocksinglewas.order.entity;

import java.time.Instant;

/**
 * 주문(EA: 주문 관리::주문). orders 테이블 한 행.
 *
 * @param id            주문번호. 저장 전에는 null
 * @param clientOrderId 요청번호. 같은 계좌에서 재요청을 알아보는 키
 * @param quantity      주문수량
 * @param remaining     잔여수량
 * @param seq           처리순번. 종목 워커가 처음 처리할 때 부여하며 RECEIVED는 null
 * @param createdAt     접수시각. 저장 전에는 null
 */
public record Order(
        Long id,
        long accountId,
        String symbolCode,
        String clientOrderId,
        Side side,
        long limitPrice,
        int quantity,
        int remaining,
        OrderStatus status,
        Long seq,
        Instant createdAt) {

    /** T1에서 저장할 새 주문. */
    public static Order received(long accountId, String symbolCode, String clientOrderId,
                                 Side side, long limitPrice, int quantity) {
        return new Order(null, accountId, symbolCode, clientOrderId, side, limitPrice,
                quantity, quantity, OrderStatus.RECEIVED, null, null);
    }

    /** 같은 요청번호로 다시 온 요청이 처음 요청과 같은 주문인지. */
    public boolean sameRequestAs(String symbolCode, Side side, long limitPrice, int quantity) {
        return this.symbolCode.equals(symbolCode)
                && this.side == side
                && this.limitPrice == limitPrice
                && this.quantity == quantity;
    }
}
