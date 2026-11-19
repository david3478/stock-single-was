package com.example.stocksinglewas.dao;

import java.sql.PreparedStatement;
import java.sql.Statement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.stocksinglewas.matching.Fill;

/** 체결DAO. 호출자의 T2 안에서만 쓴다. */
@Repository
public class TradeDao {

    private final JdbcTemplate jdbc;

    public TradeDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 저장: 체결 1건을 INSERT하고 체결번호를 돌려준다. */
    public long insert(Fill fill) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO trades (buy_order_id, sell_order_id, price, quantity)
                    VALUES (?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, fill.buyOrderId());
            ps.setLong(2, fill.sellOrderId());
            ps.setLong(3, fill.price());
            ps.setInt(4, fill.quantity());
            return ps;
        }, key);
        return key.getKey().longValue();
    }
}
