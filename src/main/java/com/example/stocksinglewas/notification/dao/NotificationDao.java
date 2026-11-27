package com.example.stocksinglewas.notification.dao;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.stocksinglewas.notification.dto.NotificationView;
import com.example.stocksinglewas.notification.entity.NotificationType;
import com.example.stocksinglewas.order.entity.OrderStatus;

/** 알림DAO. INSERT는 알림기록기만, 조회는 알림전송Service가 호출한다(ADR 0003). */
@Repository
public class NotificationDao {

    private final JdbcTemplate jdbc;

    public NotificationDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 번호이후묶음조회. 같은 계좌의 알림 번호 순서는 커밋 순서와 같으므로(ADR 0003)
     * 마지막으로 받은 번호 다음부터 읽으면 커밋된 알림을 건너뛰지 않는다.
     */
    public List<NotificationView> findAfter(long accountId, long lastEventId, int limit) {
        return jdbc.query("""
                SELECT id, type, payload FROM notifications
                WHERE account_id = ? AND id > ?
                ORDER BY id
                LIMIT ?""",
                (rs, n) -> new NotificationView(rs.getLong("id"), rs.getString("type"), rs.getString("payload")),
                accountId, lastEventId, limit);
    }

    /** 그 계좌의 가장 최근 알림 번호. 없으면 0. */
    public long latestId(long accountId) {
        Long id = jdbc.queryForObject(
                "SELECT MAX(id) FROM notifications WHERE account_id = ?", Long.class, accountId);
        return id == null ? 0 : id;
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
