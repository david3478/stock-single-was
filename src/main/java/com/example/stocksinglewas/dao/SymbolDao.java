package com.example.stocksinglewas.dao;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.stocksinglewas.entity.Symbol;
import com.example.stocksinglewas.entity.SymbolStatus;

/** 종목DAO. */
@Repository
public class SymbolDao {

    private final JdbcTemplate jdbc;

    public SymbolDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Symbol> find(String code) {
        return jdbc.query("SELECT code, name, status FROM symbols WHERE code = ?",
                (rs, n) -> new Symbol(rs.getString("code"), rs.getString("name"),
                        SymbolStatus.valueOf(rs.getString("status"))),
                code).stream().findFirst();
    }
}
