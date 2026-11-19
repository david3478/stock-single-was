package com.example.stocksinglewas.order.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.stocksinglewas.order.entity.Order;
import com.example.stocksinglewas.order.entity.OrderStatus;
import com.example.stocksinglewas.order.entity.Side;

/** 주문DAO. */
@Repository
public class OrderDao {

    private static final RowMapper<Order> ORDER = OrderDao::map;

    private final JdbcTemplate jdbc;

    public OrderDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 기존주문찾기: 같은 계좌의 같은 요청번호 주문. */
    public Optional<Order> findByClientOrderId(long accountId, String clientOrderId) {
        return jdbc.query("SELECT * FROM orders WHERE account_id = ? AND client_order_id = ?",
                ORDER, accountId, clientOrderId).stream().findFirst();
    }

    /** 주문조회. */
    public Optional<Order> findById(long orderId) {
        return jdbc.query("SELECT * FROM orders WHERE id = ?", ORDER, orderId).stream().findFirst();
    }

    /**
     * 미처리선두묶음조회: 종목의 RECEIVED 주문번호를 ID 순으로 앞에서부터 읽는다.
     * 영구 커서를 쓰지 않는다. 늦게 커밋된 작은 ID도 다음 조회에 나타나야 하기 때문이다(ADR 0002, 0005).
     */
    public List<Long> findReceivedIds(String symbolCode, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM orders WHERE symbol_code = ? AND status = 'RECEIVED' ORDER BY id LIMIT ?""",
                Long.class, symbolCode, limit);
    }

    /** 잔여주문조회: 호가창에 되살릴 대기 주문. */
    public List<Order> findResting(String symbolCode) {
        return jdbc.query("""
                SELECT * FROM orders
                WHERE symbol_code = ? AND status IN ('OPEN', 'PARTIALLY_FILLED')""",
                ORDER, symbolCode);
    }

    /** 최대처리순번조회. 완료된 주문까지 포함해야 새 순번이 기존 순번과 겹치지 않는다. 없으면 0. */
    public long maxSeq(String symbolCode) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(seq), 0) FROM orders WHERE symbol_code = ?",
                Long.class, symbolCode);
    }

    /** 입력 주문의 첫 처리 결과를 기록한다. RECEIVED일 때만 바뀌며, 이미 처리됐으면 false. */
    public boolean markProcessed(long orderId, long seq, OrderStatus status, int remaining) {
        return jdbc.update("""
                UPDATE orders SET seq = ?, status = ?, remaining = ?
                WHERE id = ? AND status = 'RECEIVED'""",
                seq, status.name(), remaining, orderId) == 1;
    }

    /** 대기 주문의 잔량을 줄인다. 잔량이 예상과 다르면 false(메모리와 DB 불일치). */
    public boolean updateResting(long orderId, int expectedRemaining, int newRemaining, OrderStatus status) {
        return jdbc.update("""
                UPDATE orders SET remaining = ?, status = ?
                WHERE id = ? AND remaining = ? AND status IN ('OPEN', 'PARTIALLY_FILLED')""",
                newRemaining, status.name(), orderId, expectedRemaining) == 1;
    }

    /** 저장: RECEIVED 주문을 INSERT하고 발급된 주문번호를 돌려준다. */
    public long insert(Order order) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO orders (account_id, symbol_code, client_order_id, side, limit_price,
                                        qty, remaining, status, seq)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, order.accountId());
            ps.setString(2, order.symbolCode());
            ps.setString(3, order.clientOrderId());
            ps.setString(4, order.side().name());
            ps.setLong(5, order.limitPrice());
            ps.setInt(6, order.quantity());
            ps.setInt(7, order.remaining());
            ps.setString(8, order.status().name());
            ps.setObject(9, order.seq());
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    private static Order map(ResultSet rs, int rowNum) throws SQLException {
        return new Order(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("symbol_code"),
                rs.getString("client_order_id"),
                Side.valueOf(rs.getString("side")),
                rs.getLong("limit_price"),
                rs.getInt("qty"),
                rs.getInt("remaining"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getObject("seq", Long.class),
                rs.getTimestamp("created_at").toInstant());
    }
}
