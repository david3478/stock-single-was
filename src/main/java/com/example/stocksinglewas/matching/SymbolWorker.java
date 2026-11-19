package com.example.stocksinglewas.matching;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;

import com.example.stocksinglewas.config.MatchingProperties;
import com.example.stocksinglewas.dao.OrderDao;
import com.example.stocksinglewas.entity.Order;
import com.example.stocksinglewas.entity.OrderStatus;
import com.example.stocksinglewas.service.MatchConfirmService;

/**
 * 종목체결워커. 종목 하나에 스레드 하나이며, 이 스레드만 그 종목의 호가창을 바꾼다.
 *
 * <p>주문 하나의 처리 순서:
 * <ol>
 *   <li>DB에서 주문을 읽는다. RECEIVED가 아니면 이미 처리된 중복 전달이므로 건너뛴다.</li>
 *   <li>호가창을 바꾸지 않고 체결 계획을 만든다.</li>
 *   <li>체결확정Service로 T2를 커밋한다. 데드락이면 T2 전체를 다시 시도한다.</li>
 *   <li>커밋이 성공한 뒤에만 계획을 호가창에 반영하고 처리순번을 하나 올린다.</li>
 * </ol>
 * T2가 실패하면 계획을 버리기만 하면 되므로 호가창은 그대로다.
 */
public final class SymbolWorker {

    private static final Logger log = LoggerFactory.getLogger(SymbolWorker.class);

    public enum Outcome { APPLIED, SKIPPED }

    public enum State { CREATED, RUNNING, STOPPED, FAILED }

    private final String symbolCode;
    private final SymbolQueue queue;
    private final OrderBook book;
    private final OrderDao orders;
    private final MatchConfirmService confirm;
    private final MatchingProperties props;

    private long nextSeq;
    private volatile boolean running;
    private volatile State state = State.CREATED;
    private Thread thread;

    public SymbolWorker(String symbolCode, SymbolQueue queue, OrderBook book, long nextSeq,
                        OrderDao orders, MatchConfirmService confirm, MatchingProperties props) {
        this.symbolCode = symbolCode;
        this.queue = queue;
        this.book = book;
        this.nextSeq = nextSeq;
        this.orders = orders;
        this.confirm = confirm;
        this.props = props;
    }

    /** 주문 하나를 처리한다. 예외가 나면 호가창과 순번은 바뀌지 않는다. */
    public Outcome process(long orderId) {
        Optional<Order> found = orders.findById(orderId);
        if (found.isEmpty() || found.get().status() != OrderStatus.RECEIVED) {
            return Outcome.SKIPPED;
        }
        Order o = found.get();
        MatchPlan plan = book.plan(
                new IncomingOrder(o.id(), o.accountId(), o.side(), o.limitPrice(), o.quantity()), nextSeq);
        if (!confirmWithRetry(plan)) {
            return Outcome.SKIPPED;
        }
        book.apply(plan);
        nextSeq++;
        return Outcome.APPLIED;
    }

    /**
     * 대기열이 넘친 동안 DB의 RECEIVED를 ID 순으로 한 묶음 처리한다(ADR 0005 ①).
     * 비어 있으면 넘침을 해제하고 한 번 더 조회한다. 해제 직전에 DB에만 남은 주문을 놓치지 않기 위해서다.
     * 그 사이 큐와 DB 양쪽에 들어간 주문은 두 번째 처리에서 RECEIVED가 아니므로 건너뛴다.
     */
    void drainFromDb() {
        List<Long> ids = orders.findReceivedIds(symbolCode, props.drainBatchSize());
        if (ids.isEmpty()) {
            queue.clearOverflow();
            ids = orders.findReceivedIds(symbolCode, props.drainBatchSize());
            if (ids.isEmpty()) {
                return;
            }
            queue.markOverflow();
        }
        ids.forEach(this::process);
    }

    private boolean confirmWithRetry(MatchPlan plan) {
        for (int attempt = 0; ; attempt++) {
            try {
                return confirm.confirm(symbolCode, plan);
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= props.deadlockMaxRetries()) {
                    throw e;
                }
                log.info("deadlock on order {} ({}), retrying T2", plan.incoming().orderId(), symbolCode);
                sleep(props.deadlockRetryDelay());
            }
        }
    }

    public long nextSeq() {
        return nextSeq;
    }

    public State state() {
        return state;
    }

    public void start() {
        running = true;
        state = State.RUNNING;
        thread = Thread.ofPlatform().name("worker-" + symbolCode).start(this::loop);
    }

    /** 처리 중인 주문을 끝낸 뒤 멈춘다. */
    public void stop() {
        running = false;
        if (thread == null) {
            return;
        }
        try {
            thread.join(props.pollTimeout().multipliedBy(20).toMillis());
            if (thread.isAlive()) {
                thread.interrupt();
                thread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void loop() {
        try {
            while (running) {
                Long orderId = queue.poll(props.pollTimeout());
                if (orderId != null) {
                    process(orderId);
                } else if (queue.overflowed()) {
                    drainFromDb();
                }
            }
            state = State.STOPPED;
        } catch (InterruptedException e) {
            state = State.STOPPED;
        } catch (RuntimeException e) {
            // DB와 메모리가 어긋났거나 복구할 수 없는 오류. 이 종목만 멈춘다. 종목 단위 복구는 7단계.
            state = State.FAILED;
            log.error("symbol worker {} stopped; orders stay RECEIVED in DB", symbolCode, e);
        }
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry", e);
        }
    }
}
