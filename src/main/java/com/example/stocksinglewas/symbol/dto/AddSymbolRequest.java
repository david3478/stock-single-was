package com.example.stocksinglewas.symbol.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 종목추가요청.
 *
 * @param code 종목 코드. 영문 대문자와 숫자 1~20자(symbols.code VARCHAR(20))
 * @param name 종목 이름(symbols.name VARCHAR(100))
 */
public record AddSymbolRequest(
        @NotBlank @Pattern(regexp = "[A-Z0-9]{1,20}") String code,
        @NotBlank @Size(max = 100) String name) {
}
