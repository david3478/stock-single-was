package com.example.stocksinglewas.matching.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.example.stocksinglewas.global.config.MatchingProperties;
import com.example.stocksinglewas.order.dao.OrderDao;
import com.example.stocksinglewas.symbol.dao.SymbolDao;
import com.example.stocksinglewas.order.entity.Order;
import com.example.stocksinglewas.order.entity.Side;
import com.example.stocksinglewas.matching.service.MatchConfirmService;
import com.example.stocksinglewas.notification.service.NotificationPushService;

import jakarta.annotation.PreDestroy;

/**
 * 종목체결준비기. 종목마다 입력 대기열, 대기주문목록, 워커를 만들어 시작한다.
 *
 * <p>준비 순서: DB의 대기 주문으로 호가창 복원 → 다음 처리순번 = MAX(seq) + 1 →
 * 대기열을 넘침 상태로 만들어 전달기에 등록 → 워커 시작.
 * 넘침 상태로 시작하므로 워커는 DB에 남아 있던 RECEIVED 주문부터 ID 순으로 처리한다.
 * 기동, 종목 추가, 종목 단위 복구가 모두 이 경로를 쓴다.
 */
@Component
public class SymbolPreparer {

    private static final Logger log = LoggerFactory.getLogger(SymbolPreparer.class);

    private final OrderDao orders;
    private final SymbolDao symbols;
    private final MatchConfirmService confirm;
    private final NotificationPushService push;
    private final OrderDispatcher dispatcher;
    private final MatchingProperties props;
    private final Map<String, SymbolWorker> workers = new ConcurrentHashMap<>();

    public SymbolPreparer(OrderDao orders, SymbolDao symbols, MatchConfirmService confirm,
                          NotificationPushService push, OrderDispatcher dispatcher, MatchingProperties props) {
        this.orders = orders;
        this.symbols = symbols;
        this.confirm = confirm;
        this.push = push;
        this.dispatcher = dispatcher;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prepareActiveSymbols() {
        if (props.autoStart()) {
            symbols.findActive().forEach(s -> prepare(s.code()));
        }
    }

    /** 새종목준비. 이미 엔진이 있으면 멈춘 뒤 DB에서 다시 만든다. 같은 종목 워커는 하나뿐이다. */
    public synchronized void prepare(String symbolCode) {
        stop(symbolCode);

        OrderBook book = new OrderBook();
        for (Order o : orders.findResting(symbolCode)) {
            book.add(new RestingOrder(o.id(), o.accountId(), o.side(), o.limitPrice(), o.remaining(), o.seq()));
        }
        long nextSeq = orders.maxSeq(symbolCode) + 1;
        SymbolQueue queue = new SymbolQueue(props.queueCapacity());
        queue.markOverflow();

        SymbolWorker worker = new SymbolWorker(symbolCode, queue, book, nextSeq, orders, confirm, push, props);
        workers.put(symbolCode, worker);
        dispatcher.register(symbolCode, queue);
        worker.start();
        log.info("symbol {} ready: {} resting orders, next seq {}",
                symbolCode, book.orders(Side.BUY).size()
                        + book.orders(Side.SELL).size(), nextSeq);
    }

    @PreDestroy
    public synchronized void stopAll() {
        workers.keySet().forEach(this::stop);
    }

    private void stop(String symbolCode) {
        SymbolWorker worker = workers.remove(symbolCode);
        dispatcher.unregister(symbolCode);
        if (worker != null) {
            worker.stop();
        }
    }
}
