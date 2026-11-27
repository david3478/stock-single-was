package com.example.stocksinglewas.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.stocksinglewas.matching.engine.EngineStatus;
import com.example.stocksinglewas.matching.engine.SymbolPreparer;
import com.example.stocksinglewas.matching.engine.SymbolWorker.State;
import com.example.stocksinglewas.order.dao.OrderDao;
import com.example.stocksinglewas.order.dto.ReceivedSummary;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 체결감시(ADR 0005 감시, ADR 0013 경고만). 엔진은 쉬고 있는데 DB에 미처리 주문이 있으면 전달이 빠진 것이다.
 * 커밋 직후·전달 직전의 정상적인 순간을 오판하지 않도록 두 번 연속 관찰될 때만 경고한다.
 * 엔진 상태와 DB 조회 결과로 판정하는 규칙이므로 둘 다 mock으로 대신한다.
 */
class MatchingMonitorTest {

    SymbolPreparer preparer;
    OrderDao orders;
    SimpleMeterRegistry meters;
    MatchingMonitor monitor;

    @BeforeEach
    void setUp() {
        preparer = mock(SymbolPreparer.class);
        orders = mock(OrderDao.class);
        meters = new SimpleMeterRegistry();
        monitor = new MatchingMonitor(preparer, orders, meters);
        when(preparer.symbols()).thenReturn(List.of("AAA"));
    }

    void engine(State state, boolean busy, int queueSize, boolean overflowed) {
        when(preparer.status("AAA")).thenReturn(
                Optional.of(new EngineStatus("AAA", state, busy, queueSize, overflowed, 0, 1)));
    }

    void received(long count, double oldestAgeSeconds) {
        when(orders.findReceivedSummary("AAA")).thenReturn(new ReceivedSummary(count, oldestAgeSeconds));
    }

    double stalls() {
        return meters.counter("matching.stall.detected", "symbol", "AAA").count();
    }

    double gauge(String name) {
        return meters.get(name).tag("symbol", "AAA").gauge().value();
    }

    // --- 이상 감지 ---

    @Test
    void 엔진이_쉬는데_DB에_미처리_주문이_두_번_연속_보이면_경고한다() {
        engine(State.RUNNING, false, 0, false);
        received(3, 12.0);

        monitor.check();
        assertThat(stalls()).as("한 번만 보면 커밋 직후일 수 있다").isZero();
        monitor.check();
        assertThat(stalls()).isEqualTo(1);
    }

    @Test
    void 이상이_계속되어도_경고는_처음_감지할_때_한_번_센다() {
        engine(State.RUNNING, false, 0, false);
        received(3, 12.0);

        monitor.check();
        monitor.check();
        monitor.check();

        assertThat(stalls()).isEqualTo(1);
    }

    @Test
    void 중간에_정상으로_돌아오면_연속_횟수를_처음부터_센다() {
        engine(State.RUNNING, false, 0, false);
        received(3, 1.0);
        monitor.check();
        received(0, 0);
        monitor.check();
        received(3, 1.0);
        monitor.check();

        assertThat(stalls()).isZero();
    }

    @Test
    void 대기열에_주문이_있으면_정상_적체로_보고_경고하지_않는다() {
        engine(State.RUNNING, false, 50, false);
        received(500, 3.0);

        monitor.check();
        monitor.check();

        assertThat(stalls()).isZero();
    }

    @Test
    void 넘침_중이면_워커가_DB에서_이어_처리하므로_경고하지_않는다() {
        engine(State.RUNNING, false, 0, true);
        received(30_000, 5.0);

        monitor.check();
        monitor.check();

        assertThat(stalls()).isZero();
    }

    @Test
    void 워커가_주문을_처리하는_중이면_경고하지_않는다() {
        engine(State.RUNNING, true, 0, false);
        received(1, 0.1);

        monitor.check();
        monitor.check();

        assertThat(stalls()).isZero();
    }

    // --- 메트릭 ---

    @Test
    void 종목별_미처리_건수와_가장_오래된_대기_시간과_대기열_상태를_메트릭으로_내보낸다() {
        engine(State.RUNNING, false, 7, true);
        received(42, 3.5);

        monitor.check();

        assertThat(gauge("matching.orders.received")).isEqualTo(42);
        assertThat(gauge("matching.orders.received.oldest.age")).isEqualTo(3.5);
        assertThat(gauge("matching.queue.size")).isEqualTo(7);
        assertThat(gauge("matching.queue.overflow")).isEqualTo(1);
        assertThat(gauge("matching.worker.failed")).isZero();
    }

    @Test
    void 워커가_FAILED면_메트릭에_바로_보인다() {
        engine(State.RUNNING, false, 0, false);
        received(0, 0);
        monitor.check();

        engine(State.FAILED, false, 0, false);

        assertThat(gauge("matching.worker.failed")).as("다음 감시 주기를 기다리지 않고 조회 시점에 읽는다").isEqualTo(1);
    }

    @Test
    void DB_조회가_실패해도_다른_종목_감시를_계속한다() {
        when(preparer.symbols()).thenReturn(List.of("AAA", "BBB"));
        engine(State.RUNNING, false, 0, false);
        when(preparer.status("BBB")).thenReturn(
                Optional.of(new EngineStatus("BBB", State.RUNNING, false, 0, false, 0, 1)));
        when(orders.findReceivedSummary("AAA")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        when(orders.findReceivedSummary("BBB")).thenReturn(new ReceivedSummary(2, 1.0));

        monitor.check();

        assertThat(meters.get("matching.orders.received").tag("symbol", "BBB").gauge().value()).isEqualTo(2);
    }
}
