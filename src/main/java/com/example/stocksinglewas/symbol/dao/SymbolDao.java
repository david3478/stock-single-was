package com.example.stocksinglewas.symbol.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.stocksinglewas.symbol.entity.Symbol;
import com.example.stocksinglewas.symbol.entity.SymbolStatus;

/** 종목DAO. */
@Repository
public class SymbolDao {

    private final JdbcTemplate jdbc;

    public SymbolDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Symbol> SYMBOL = (rs, n) -> new Symbol(
            rs.getString("code"), rs.getString("name"), SymbolStatus.valueOf(rs.getString("status")));

    /** 종목조회. */
    public Optional<Symbol> find(String code) {
        return jdbc.query("SELECT code, name, status FROM symbols WHERE code = ?", SYMBOL, code)
                .stream().findFirst();
    }

    /** 활성종목조회. */
    public List<Symbol> findActive() {
        return jdbc.query("SELECT code, name, status FROM symbols WHERE status = 'ACTIVE' ORDER BY code", SYMBOL);
    }
}
