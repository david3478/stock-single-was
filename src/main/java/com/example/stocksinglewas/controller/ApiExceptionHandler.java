package com.example.stocksinglewas.controller;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.stocksinglewas.dto.ErrorResponse;
import com.example.stocksinglewas.service.OrderRejectedException;

/** 예외를 HTTP 상태와 오류 코드로 바꾼다. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(OrderRejectedException.class)
    ResponseEntity<ErrorResponse> rejected(OrderRejectedException e) {
        HttpStatus status = switch (e.reason()) {
            case ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case REQUEST_ID_CONFLICT -> HttpStatus.CONFLICT;
            case AMOUNT_OUT_OF_RANGE -> HttpStatus.BAD_REQUEST;
            case SYMBOL_NOT_TRADABLE, INSUFFICIENT_CASH, INSUFFICIENT_QUANTITY -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(e.reason().name(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", "malformed request body"));
    }
}
