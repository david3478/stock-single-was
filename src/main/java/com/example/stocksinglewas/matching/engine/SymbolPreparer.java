package com.example.stocksinglewas.matching.engine;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.contributor.Health;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.example.stocksinglewas.global.config.MatchingProperties;
import com.example.stocksinglewas.global.config.RecoveryProperties;
import com.example.stocksinglewas.matching.service.MatchConfirmService;
import com.example.stocksinglewas.notification.service.NotificationPushService;
import com.example.stocksinglewas.order.dao.OrderDao;
import com.example.stocksinglewas.order.entity.Order;
import com.example.stocksinglewas.order.entity.OrderStatus;
import com.example.stocksinglewas.symbol.dao.SymbolDao;

import jakarta.annotation.PreDestroy;

/**
 * 종목체결준비기. 종목마다 입력 대기열, 대기주문목록, 워커를 만들어 시작한다.
 *
 * <p>준비 순서: DB의 대기 주문으로 호가창 복원 → 다음 처리순번 = MAX(seq) + 1 →
 * 대기열을 넘침 상태로 만들어 전달기에 등록 → 워커 시작.
 * 넘침 상태로 시작하므로 워커는 DB에 남아 있던 RECEIVED 주문부터 ID 순으로 처리한다.
 * 기동, 종목 추가가 이 경로를 쓴다(ADR 0008). 종목 단위 자동 복구는 향후 과제다(ADR 0013).
 *
 * <p>기동 복구: 서버가 뜰 때 ACTIVE 종목을 모두 준비한 뒤에야 readiness를 연다.
 * 그동안에도 접수는 받는다. 접수된 주문은 DB에 RECEIVED로 남고 엔진이 뜬 뒤 처리된다.
 */
@Component
public class SymbolPreparer {

    private static final Logger log = LoggerFactory.getLogger(SymbolPreparer.class);
    private static final List<OrderStatus> RESTING = List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED);

    private final OrderDao orders;
    private final SymbolDao symbols;
    private final MatchConfirmService confirm;
    private final NotificationPushService push;
    private final OrderDispatcher dispatcher;
    private final MatchingProperties props;
    private final RecoveryProperties recovery;
    private final Map<String, Engine> engines = new ConcurrentHashMap<>();
    private volatile boolean startupRecovered;

    public SymbolPreparer(OrderDao orders, SymbolDao symbols, MatchConfirmService confirm,
                          NotificationPushService push, OrderDispatcher dispatcher, MatchingProperties props,
                          RecoveryProperties recovery) {
        this.orders = orders;
        this.symbols = symbols;
        this.confirm = confirm;
        this.push = push;
        this.dispatcher = dispatcher;
        this.props = props;
        this.recovery = recovery;
    }

    /**
     * 기동 복구(EA 서버기동리스너.서버기동시). 종목 하나라도 실패하면 예외가 나가 서버 기동이 실패하고,
     * readiness는 열리지 않는다(ADR 0013).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void prepareActiveSymbols() {
        if (props.autoStart()) {
            long started = System.nanoTime();
            symbols.findActive().forEach(s -> prepare(s.code()));
            log.info("startup recovery done: {} symbols in {} ms",
                    engines.size(), (System.nanoTime() - started) / 1_000_000);
        }
        startupRecovered = true;
    }

    /** 새종목준비. 이미 엔진이 있으면 멈춘 뒤 DB에서 다시 만든다. 같은 종목 워커는 하나뿐이다. */
    public synchronized void prepare(String symbolCode) {
        stop(symbolCode);
        long started = System.nanoTime();

        OrderBook book = new OrderBook();
        int restored = restoreResting(symbolCode, book);
        long nextSeq = orders.maxSeq(symbolCode) + 1;
        SymbolQueue queue = new SymbolQueue(props.queueCapacity());
        queue.markOverflow();

        SymbolWorker worker = new SymbolWorker(symbolCode, queue, book, nextSeq, orders, confirm, push, props);
        engines.put(symbolCode, new Engine(queue, worker, restored));
        dispatcher.register(symbolCode, queue);
        worker.start();
        log.info("symbol {} ready: {} resting orders, next seq {}, {} ms",
                symbolCode, restored, nextSeq, (System.nanoTime() - started) / 1_000_000);
    }

    /**
     * 잔여주문복원. 상태별로 정해진 건수씩 주문번호 순으로 이어서 읽어 원래 처리순번(seq)으로 호가창에 넣는다.
     * 호가창이 (가격, seq)로 정렬하므로 읽는 순서는 결과에 영향이 없다.
     */
    private int restoreResting(String symbolCode, OrderBook book) {
        int batch = recovery.restoreBatchSize();
        int restored = 0;
        for (OrderStatus status : RESTING) {
            long lastId = 0;
            List<Order> page;
            do {
                page = orders.findRestingAfter(symbolCode, status, lastId, batch);
                for (Order o : page) {
                    book.add(new RestingOrder(o.id(), o.accountId(), o.side(), o.limitPrice(), o.remaining(), o.seq()));
                    lastId = o.id();
                }
                restored += page.size();
            } while (page.size() == batch);
        }
        return restored;
    }

    /** 엔진이 있는 종목 목록. */
    public List<String> symbols() {
        return List.copyOf(engines.keySet());
    }

    /** 종목 엔진의 현재 상태. 엔진이 없으면 빈 값. */
    public Optional<EngineStatus> status(String symbolCode) {
        Engine e = engines.get(symbolCode);
        if (e == null) {
            return Optional.empty();
        }
        return Optional.of(new EngineStatus(symbolCode, e.worker.state(), e.worker.busy(), e.queue.size(),
                e.queue.overflowed(), e.restored, e.worker.nextSeq()));
    }

    /** readiness 판정. 기동 복구가 끝나기 전에는 OUT_OF_SERVICE. */
    public Health health() {
        if (!startupRecovered) {
            return Health.outOfService().withDetail("reason", "startup recovery in progress").build();
        }
        return Health.up().withDetail("symbols", engines.size()).build();
    }

    @PreDestroy
    public synchronized void stopAll() {
        engines.keySet().forEach(this::stop);
    }

    private void stop(String symbolCode) {
        Engine engine = engines.remove(symbolCode);
        dispatcher.unregister(symbolCode);
        if (engine != null) {
            engine.worker.stop();
        }
    }

    private record Engine(SymbolQueue queue, SymbolWorker worker, int restored) {
    }
}
