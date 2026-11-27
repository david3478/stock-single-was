package com.example.stocksinglewas.symbol.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.stocksinglewas.global.exception.SymbolConflictException;
import com.example.stocksinglewas.matching.engine.SymbolPreparer;
import com.example.stocksinglewas.symbol.dao.SymbolDao;
import com.example.stocksinglewas.symbol.entity.Symbol;
import com.example.stocksinglewas.symbol.entity.SymbolStatus;

/**
 * 종목등록Service(ADR 0014). 실행 중에 종목을 추가한다: ① 준비중 등록 → ② 엔진 준비 → ③ 활성화.
 *
 * <p>엔진을 준비한 뒤에 활성화한다. 접수는 ACTIVE 종목만 받으므로 주문은 엔진이 준비된 뒤에만 들어온다.
 * 어느 단계에서 실패해도 같은 요청을 다시 보내면 남은 단계를 이어서 끝낸다. 각 단계는 여러 번 실행해도 결과가 같다:
 * 등록은 이미 있으면 건너뛰고, 엔진은 없을 때만 만들고, 활성화는 준비 중일 때만 바꾼다.
 * 응답은 활성화가 끝난 뒤에 하므로, 응답을 받은 시험·운영자는 그 종목에 바로 주문할 수 있다.
 */
@Service
public class SymbolRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(SymbolRegistrationService.class);

    /** 이 요청이 활성화했으면 ACTIVATED(201), 이미 활성이었으면 ALREADY_ACTIVE(200). */
    public enum Outcome { ACTIVATED, ALREADY_ACTIVE }

    private final SymbolDao symbols;
    private final SymbolPreparer preparer;

    public SymbolRegistrationService(SymbolDao symbols, SymbolPreparer preparer) {
        this.symbols = symbols;
        this.preparer = preparer;
    }

    /** 종목추가. */
    public Outcome addSymbol(String code, String name) {
        Optional<Symbol> existing = symbols.find(code);
        if (existing.isEmpty() && !symbols.insertPreparing(code, name)) {
            // 동시에 들어온 다른 요청이 먼저 등록했다. 그 등록을 이어서 처리한다
            existing = symbols.find(code);
        }
        if (existing.isPresent()) {
            Symbol s = existing.get();
            if (!s.name().equals(name)) {
                throw new SymbolConflictException(code, s.name(), name);
            }
            if (s.status() == SymbolStatus.ACTIVE) {
                preparer.prepareIfAbsent(code);   // 활성인데 엔진이 없는 경우(재시작 직후 등)만 만든다
                return Outcome.ALREADY_ACTIVE;
            }
        }

        preparer.prepareIfAbsent(code);
        if (!symbols.activate(code)) {
            return Outcome.ALREADY_ACTIVE;   // 동시에 들어온 다른 요청이 먼저 활성화했다
        }
        log.info("symbol {} ({}) added and activated", code, name);
        return Outcome.ACTIVATED;
    }
}
