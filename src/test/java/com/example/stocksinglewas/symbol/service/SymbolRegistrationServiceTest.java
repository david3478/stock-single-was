package com.example.stocksinglewas.symbol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

import com.example.stocksinglewas.global.exception.SymbolConflictException;
import com.example.stocksinglewas.matching.engine.SymbolPreparer;
import com.example.stocksinglewas.symbol.dao.SymbolDao;
import com.example.stocksinglewas.symbol.entity.Symbol;
import com.example.stocksinglewas.symbol.entity.SymbolStatus;
import com.example.stocksinglewas.symbol.service.SymbolRegistrationService.Outcome;

/**
 * 종목등록Service(ADR 0014). 준비중 등록 → 엔진 준비 → 활성화 순서와, 어느 단계에서 실패해도 같은 요청으로 이어서 끝내는 규칙.
 * 호출 순서와 분기가 결과를 정하므로 DAO와 준비기는 mock으로 대신한다. SQL 조건(조건부 UPDATE, 중복 키)은 통합 테스트에서 본다.
 */
class SymbolRegistrationServiceTest {

    SymbolDao symbols;
    SymbolPreparer preparer;
    SymbolRegistrationService service;

    @BeforeEach
    void setUp() {
        symbols = mock(SymbolDao.class);
        preparer = mock(SymbolPreparer.class);
        service = new SymbolRegistrationService(symbols, preparer);
        when(symbols.find(anyString())).thenReturn(Optional.empty());
        when(symbols.insertPreparing(anyString(), anyString())).thenReturn(true);
        when(symbols.activate(anyString())).thenReturn(true);
    }

    void existing(SymbolStatus status, String name) {
        when(symbols.find("NEW1")).thenReturn(Optional.of(new Symbol("NEW1", name, status)));
    }

    // --- 성공 ---

    @Test
    void 새_종목은_준비중_등록_엔진_준비_활성화_순서로_추가한다() {
        Outcome outcome = service.addSymbol("NEW1", "신규1");

        assertThat(outcome).isEqualTo(Outcome.ACTIVATED);
        InOrder order = inOrder(symbols, preparer);
        order.verify(symbols).insertPreparing("NEW1", "신규1");
        order.verify(preparer).prepareIfAbsent("NEW1");
        order.verify(symbols).activate("NEW1");
    }

    @Test
    void 준비중으로_남은_종목을_다시_요청하면_등록을_건너뛰고_이어서_활성화한다() {
        existing(SymbolStatus.PREPARING, "신규1");

        assertThat(service.addSymbol("NEW1", "신규1")).isEqualTo(Outcome.ACTIVATED);

        verify(symbols, never()).insertPreparing(anyString(), anyString());
        InOrder order = inOrder(preparer, symbols);
        order.verify(preparer).prepareIfAbsent("NEW1");
        order.verify(symbols).activate("NEW1");
    }

    @Test
    void 이미_활성인_종목은_다시_활성화하지_않고_이미_활성으로_응답한다() {
        existing(SymbolStatus.ACTIVE, "신규1");

        assertThat(service.addSymbol("NEW1", "신규1")).isEqualTo(Outcome.ALREADY_ACTIVE);

        verify(symbols, never()).insertPreparing(anyString(), anyString());
        verify(symbols, never()).activate(anyString());
    }

    @Test
    void 이미_활성인_종목도_엔진이_없으면_엔진을_만든다() {
        existing(SymbolStatus.ACTIVE, "신규1");

        service.addSymbol("NEW1", "신규1");

        verify(preparer).prepareIfAbsent("NEW1");
    }

    @Test
    void 동시에_다른_요청이_먼저_등록했으면_그_등록을_이어서_처리한다() {
        when(symbols.insertPreparing("NEW1", "신규1")).thenReturn(false);
        when(symbols.find("NEW1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new Symbol("NEW1", "신규1", SymbolStatus.PREPARING)));

        assertThat(service.addSymbol("NEW1", "신규1")).isEqualTo(Outcome.ACTIVATED);

        verify(preparer).prepareIfAbsent("NEW1");
        verify(symbols).activate("NEW1");
    }

    @Test
    void 동시에_다른_요청이_먼저_활성화했으면_이미_활성으로_응답한다() {
        when(symbols.activate("NEW1")).thenReturn(false);

        assertThat(service.addSymbol("NEW1", "신규1")).isEqualTo(Outcome.ALREADY_ACTIVE);
    }

    // --- 실패 ---

    @Test
    void 엔진_준비가_실패하면_활성화하지_않아_주문을_받지_않는다() {
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("db down")).when(preparer).prepareIfAbsent("NEW1");

        assertThatThrownBy(() -> service.addSymbol("NEW1", "신규1"))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(symbols, never()).activate(anyString());
    }

    @Test
    void 같은_코드가_다른_이름으로_있으면_거절하고_아무것도_바꾸지_않는다() {
        existing(SymbolStatus.ACTIVE, "기존이름");

        assertThatThrownBy(() -> service.addSymbol("NEW1", "다른이름"))
                .isInstanceOf(SymbolConflictException.class);

        verify(preparer, never()).prepareIfAbsent(anyString());
        verify(symbols, never()).activate(anyString());
    }

    @Test
    void 동시에_먼저_등록된_종목의_이름이_다르면_거절한다() {
        when(symbols.insertPreparing("NEW1", "신규1")).thenReturn(false);
        when(symbols.find("NEW1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new Symbol("NEW1", "다른이름", SymbolStatus.PREPARING)));

        assertThatThrownBy(() -> service.addSymbol("NEW1", "신규1"))
                .isInstanceOf(SymbolConflictException.class);
        verify(preparer, never()).prepareIfAbsent(anyString());
    }
}
