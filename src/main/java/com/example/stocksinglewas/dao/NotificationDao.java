package com.example.stocksinglewas.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.stocksinglewas.entity.NotificationType;
import com.example.stocksinglewas.entity.OrderStatus;

/** 알림DAO. INSERT는 알림기록기만 호출한다(ADR 0003). */
@Repository
public class NotificationDao {

    private final JdbcTemplate jdbc;

    public NotificationDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 알림저장. 내용(payload)은 클라이언트가 바로 보여줄 수 있는 주문 결과다.
     *
     * @param tradeId 체결 알림이면 체결번호, 대기 등록이면 null
     * @param price   체결가. 대기 등록이면 null
     * @param quantity 체결수량. 대기 등록이면 null
     */
    public void insert(long accountId, long orderId, Long tradeId, OrderStatus status, int remaining,
                       Long price, Integer quantity) {
        jdbc.update("""
                INSERT INTO notifications (account_id, order_id, trade_id, type, payload)
                VALUES (?, ?, ?, ?, JSON_OBJECT(
                    'orderId', ?, 'status', ?, 'remaining', ?, 'tradeId', ?, 'price', ?, 'quantity', ?))""",
                accountId, orderId, tradeId, NotificationType.of(status).name(),
                orderId, status.name(), remaining, tradeId, price, quantity);
    }
}
