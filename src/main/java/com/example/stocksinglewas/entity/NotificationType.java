package com.example.stocksinglewas.entity;

/** 알림종류. 알림은 주문의 처리 결과 하나를 그 주문의 계좌에 알린다. */
public enum NotificationType {
    /** 상대가 없어 전량 대기 등록. 체결 없음. */
    ORDER_OPEN,
    /** 체결 1건으로 일부 체결 상태가 됨. */
    ORDER_PARTIALLY_FILLED,
    /** 체결 1건으로 전량 체결됨. */
    ORDER_FILLED;

    public static NotificationType of(OrderStatus status) {
        return switch (status) {
            case OPEN -> ORDER_OPEN;
            case PARTIALLY_FILLED -> ORDER_PARTIALLY_FILLED;
            case FILLED -> ORDER_FILLED;
            case RECEIVED -> throw new IllegalArgumentException("RECEIVED order has no processing result");
        };
    }
}
