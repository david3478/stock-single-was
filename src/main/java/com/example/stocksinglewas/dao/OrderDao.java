package com.example.stocksinglewas.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.stocksinglewas.entity.Order;
import com.example.stocksinglewas.entity.OrderStatus;
import com.example.stocksinglewas.entity.Side;

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
