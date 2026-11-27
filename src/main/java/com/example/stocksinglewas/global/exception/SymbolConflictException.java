package com.example.stocksinglewas.global.exception;

/** 같은 종목 코드가 다른 이름으로 이미 있음. HTTP 409(ADR 0014). */
public class SymbolConflictException extends RuntimeException {

    public SymbolConflictException(String code, String existingName, String requestedName) {
        super("symbol " + code + " already exists as '" + existingName + "', requested '" + requestedName + "'");
    }
}
