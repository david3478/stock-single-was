package com.example.stocksinglewas.global.exception;

/** 주문 접수 거절. 거절된 주문은 아무것도 저장하거나 예약하지 않는다. */
public class OrderRejectedException extends RuntimeException {

    public enum Reason {
        ACCOUNT_NOT_FOUND,
        SYMBOL_NOT_TRADABLE,
        INSUFFICIENT_CASH,
        INSUFFICIENT_QUANTITY,
        /** 지정가 × 수량이 표현 범위를 넘음. */
        AMOUNT_OUT_OF_RANGE,
        /** 같은 요청번호로 내용이 다른 주문을 보냄. */
        REQUEST_ID_CONFLICT
    }

    private final Reason reason;

    public OrderRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
