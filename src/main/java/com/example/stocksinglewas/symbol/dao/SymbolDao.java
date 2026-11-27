package com.example.stocksinglewas.symbol.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
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

    /** 전체 종목. */
    public List<Symbol> findAll() {
        return jdbc.query("SELECT code, name, status FROM symbols ORDER BY code", SYMBOL);
    }

    /** 준비중등록. 새로 넣었으면 true, 같은 코드가 이미 있으면 false(동시 요청 포함). */
    public boolean insertPreparing(String code, String name) {
        try {
            return jdbc.update("INSERT INTO symbols (code, name, status) VALUES (?, ?, 'PREPARING')", code, name) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 활성화. 준비 중인 종목만 바꾼다. 이 호출이 바꿨으면 true, 이미 ACTIVE였으면 false. */
    public boolean activate(String code) {
        return jdbc.update("UPDATE symbols SET status = 'ACTIVE' WHERE code = ? AND status = 'PREPARING'", code) == 1;
    }

    /** 활성종목조회. */
    public List<Symbol> findActive() {
        return jdbc.query("SELECT code, name, status FROM symbols WHERE status = 'ACTIVE' ORDER BY code", SYMBOL);
    }
}
