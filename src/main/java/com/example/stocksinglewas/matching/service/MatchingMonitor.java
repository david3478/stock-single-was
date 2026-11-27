package com.example.stocksinglewas.matching.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.stocksinglewas.matching.engine.EngineStatus;
import com.example.stocksinglewas.matching.engine.SymbolPreparer;
import com.example.stocksinglewas.matching.engine.SymbolWorker.State;
import com.example.stocksinglewas.order.dao.OrderDao;
import com.example.stocksinglewas.order.dto.ReceivedSummary;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 체결감시(ADR 0005 감시, ADR 0013). 종목별 상태를 메트릭으로 내보내고, 상태 불일치를 경고한다. 복구는 실행하지 않는다.
 *
 * <p>상태 불일치: 워커가 동작 중이고 주문을 처리하고 있지 않으며, 대기열이 비고 넘침도 아닌데 DB에 RECEIVED가 있다.
 * 전달이 빠진 주문이 있다는 뜻이다. 접수 커밋 직후·전달 직전의 정상적인 순간을 오판하지 않도록 두 주기 연속일 때만 경고한다.
 * 정상 적체 중에는 대기열이 비어 있지 않거나 넘침 상태이므로 이 조건에 걸리지 않는다.
 *
 * <p>대기열·넘침·워커 상태 메트릭은 조회 시점의 엔진 값을 읽는다. 워커가 FAILED가 되면 다음 감시 주기를 기다리지 않고 보인다.
 * DB에서 읽는 RECEIVED 건수와 대기 시간은 감시 주기마다 갱신한다.
 */
@Service
public class MatchingMonitor {

    private static final Logger log = LoggerFactory.getLogger(MatchingMonitor.class);
    private static final int STALL_OBSERVATIONS = 2;

    private final SymbolPreparer preparer;
    private final OrderDao orders;
    private final MeterRegistry meters;
    private final Map<String, SymbolMetrics> bySymbol = new ConcurrentHashMap<>();

    public MatchingMonitor(SymbolPreparer preparer, OrderDao orders, MeterRegistry meters) {
        this.preparer = preparer;
        this.orders = orders;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${trading.recovery.monitor-interval}")
    public void check() {
        for (String symbol : preparer.symbols()) {
            try {
                check(symbol);
            } catch (RuntimeException e) {
                log.warn("monitor check failed for symbol {}", symbol, e);
            }
        }
    }

    private void check(String symbol) {
        SymbolMetrics m = bySymbol.computeIfAbsent(symbol, this::register);
        // 엔진 상태를 먼저 읽고 DB를 본다. 그 사이 접수된 주문은 다음 주기에 정상으로 돌아온다
        Optional<EngineStatus> status = preparer.status(symbol);
        ReceivedSummary received = orders.findReceivedSummary(symbol);
        m.received = received.count();
        m.oldestAgeSeconds = received.oldestAgeSeconds();

        boolean stalled = status.isPresent() && isStalled(status.get(), received);
        if (!stalled) {
            m.consecutiveStalls = 0;
            return;
        }
        m.consecutiveStalls++;
        if (m.consecutiveStalls == STALL_OBSERVATIONS) {
            meters.counter("matching.stall.detected", "symbol", symbol).increment();
            log.warn("symbol {} engine is idle but {} orders stay RECEIVED (oldest {}s). "
                    + "Orders were not delivered to the engine; restart the server to recover (ADR 0013)",
                    symbol, received.count(), received.oldestAgeSeconds());
        }
    }

    private static boolean isStalled(EngineStatus s, ReceivedSummary received) {
        return s.state() == State.RUNNING && !s.busy() && s.queueSize() == 0 && !s.overflowed()
                && received.count() > 0;
    }

    private SymbolMetrics register(String symbol) {
        SymbolMetrics m = new SymbolMetrics();
        Gauge.builder("matching.orders.received", m, x -> x.received)
                .description("orders committed but not yet processed by the symbol worker")
                .tag("symbol", symbol).strongReference(true).register(meters);
        Gauge.builder("matching.orders.received.oldest.age", m, x -> x.oldestAgeSeconds)
                .description("seconds the oldest RECEIVED order has waited").baseUnit("seconds")
                .tag("symbol", symbol).strongReference(true).register(meters);
        Gauge.builder("matching.queue.size", preparer, p -> live(p, symbol, s -> s.queueSize()))
                .tag("symbol", symbol).strongReference(true).register(meters);
        Gauge.builder("matching.queue.overflow", preparer, p -> live(p, symbol, s -> s.overflowed() ? 1 : 0))
                .tag("symbol", symbol).strongReference(true).register(meters);
        Gauge.builder("matching.worker.failed", preparer, p -> live(p, symbol, s -> s.state() == State.FAILED ? 1 : 0))
                .description("1 when the symbol worker stopped on an error; orders wait until restart (ADR 0013)")
                .tag("symbol", symbol).strongReference(true).register(meters);
        return m;
    }

    private static double live(SymbolPreparer preparer, String symbol,
                               java.util.function.ToDoubleFunction<EngineStatus> value) {
        return preparer.status(symbol).map(value::applyAsDouble).orElse(Double.NaN);
    }

    /** 감시 주기마다 갱신하는 값. 게이지가 다른 스레드에서 읽으므로 volatile. */
    private static final class SymbolMetrics {
        volatile double received;
        volatile double oldestAgeSeconds;
        int consecutiveStalls;
    }
}
