package com.example.stocksinglewas.symbol.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.stocksinglewas.query.dto.SymbolResponse;
import com.example.stocksinglewas.symbol.dto.AddSymbolRequest;
import com.example.stocksinglewas.symbol.service.SymbolRegistrationService;
import com.example.stocksinglewas.symbol.service.SymbolRegistrationService.Outcome;

import jakarta.validation.Valid;

/**
 * 종목관리Controller(ADR 0014). 운영자가 실행 중에 종목을 추가한다.
 * 응답은 종목이 ACTIVE가 되어 주문을 받을 준비가 끝난 뒤에 한다. 새로 활성화하면 201, 이미 활성이면 200.
 * 인증이 없으므로 외부 접근은 보안 그룹으로만 제한된다(ADR 0011, 0014).
 */
@RestController
public class SymbolController {

    private final SymbolRegistrationService registration;

    public SymbolController(SymbolRegistrationService registration) {
        this.registration = registration;
    }

    @PostMapping("/api/symbols")
    public ResponseEntity<SymbolResponse> addSymbol(@Valid @RequestBody AddSymbolRequest request) {
        Outcome outcome = registration.addSymbol(request.code(), request.name());
        HttpStatus status = outcome == Outcome.ACTIVATED ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(new SymbolResponse(request.code(), request.name(), "ACTIVE"));
    }
}
