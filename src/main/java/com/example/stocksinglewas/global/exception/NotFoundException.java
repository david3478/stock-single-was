package com.example.stocksinglewas.global.exception;

/** 요청한 계좌·종목 등이 없음. HTTP 404. */
public class NotFoundException extends RuntimeException {

    private final String code;

    public NotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static NotFoundException account(long accountId) {
        return new NotFoundException("ACCOUNT_NOT_FOUND", "account " + accountId + " not found");
    }

    public static NotFoundException symbol(String symbolCode) {
        return new NotFoundException("SYMBOL_NOT_FOUND", "symbol " + symbolCode + " not found");
    }

    public String code() {
        return code;
    }
}
