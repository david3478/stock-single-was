package com.example.stocksinglewas.dto;

/** 오류 응답. code는 클라이언트가 분기할 수 있는 고정 값이다. */
public record ErrorResponse(String code, String message) {
}
