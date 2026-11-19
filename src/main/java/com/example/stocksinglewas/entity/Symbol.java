package com.example.stocksinglewas.entity;

/** 종목(EA: 주식 종목). symbols 테이블 한 행. */
public record Symbol(String code, String name, SymbolStatus status) {

    public boolean tradable() {
        return status == SymbolStatus.ACTIVE;
    }
}
