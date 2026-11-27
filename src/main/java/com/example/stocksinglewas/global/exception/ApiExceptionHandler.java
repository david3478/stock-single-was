package com.example.stocksinglewas.global.exception;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.stocksinglewas.global.exception.ErrorResponse;
import com.example.stocksinglewas.global.exception.OrderRejectedException;

/**
 * 예외를 HTTP 상태와 오류 코드로 바꾼다.
 *
 * <p>오류 본문은 항상 JSON으로 명시한다. SSE 구독처럼 Accept가 text/event-stream인 요청에서도
 * 형식 협상에 실패해 500이 되지 않고 원래 상태 코드(예: 404)를 돌려주기 위해서다.
 */
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
        return error(status, new ErrorResponse(e.reason().name(), e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, new ErrorResponse(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return error(HttpStatus.BAD_REQUEST, new ErrorResponse("INVALID_REQUEST", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, new ErrorResponse("INVALID_REQUEST", "malformed request body"));
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, ErrorResponse body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
