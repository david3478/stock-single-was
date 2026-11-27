package com.example.stocksinglewas.notification.service;

import static com.example.stocksinglewas.order.entity.Side.BUY;
import static com.example.stocksinglewas.order.entity.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.stocksinglewas.matching.engine.IncomingOrder;
import com.example.stocksinglewas.matching.engine.MatchPlan;
import com.example.stocksinglewas.matching.engine.OrderBook;
import com.example.stocksinglewas.matching.service.MatchConfirmService;
import com.example.stocksinglewas.order.dao.OrderDao;
import com.example.stocksinglewas.order.dto.PlaceOrderRequest;
import com.example.stocksinglewas.order.entity.Order;
import com.example.stocksinglewas.order.entity.Side;
import com.example.stocksinglewas.order.service.OrderIntakeService;
import com.example.stocksinglewas.support.MySqlIntegrationTest;
import com.example.stocksinglewas.support.TestData;

/**
 * ADR 0003 검증 2(스트레스). 여러 종목 워커가 같은 계좌에 동시에 알림을 만드는 동안
 * 다른 스레드가 클라이언트처럼 {@code id > 마지막ID}로 계속 이어서 읽는다.
 * 읽은 목록은 DB의 최종 목록과 같아야 한다(누락 없음, 중복 없음, 번호 순서).
 *
 * <p>종목마다 계좌 1의 매수(대기 등록)와 계좌 2의 매도(그 매수와 체결)를 번갈아 처리한다.
 * 대기 등록끼리 겹치는 경우가 명시적 계좌 잠금이 필요한 경우다(ADR 0003 ③).
 */
class NotificationCursorStressTest extends MySqlIntegrationTest {

    static final long WATCHED = 1;
    static final long COUNTERPARTY = 2;
    static final List<String> SYMBOLS = List.of("S1", "S2", "S3", "S4");
    static final int PAIRS_PER_SYMBOL = 20;
    static final int PAGE = 100;

    @Autowired
    MatchConfirmService confirm;

    @Autowired
    OrderIntakeService intake;

    @Autowired
    OrderDao orders;

    @Autowired
    JdbcTemplate jdbc;

    ExecutorService threads;

    @BeforeEach
    void seed() {
        TestData db = new TestData(jdbc);
        db.clear();
        db.account(WATCHED, 100_000_000);
        db.account(COUNTERPARTY, 0);
        for (String symbol : SYMBOLS) {
            db.symbol(symbol);
            db.holding(COUNTERPARTY, symbol, PAIRS_PER_SYMBOL);
        }
        threads = Executors.newFixedThreadPool(SYMBOLS.size() + 1);
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @Test
    void 여러_종목이_같은_계좌에_동시에_알림을_만들어도_이어서_읽으면_하나도_놓치지_않는다() throws Exception {
        List<List<Long>> orderIdsBySymbol = new ArrayList<>();
        for (String symbol : SYMBOLS) {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < PAIRS_PER_SYMBOL; i++) {
                ids.add(place(WATCHED, symbol + "-b" + i, symbol, BUY));
                ids.add(place(COUNTERPARTY, symbol + "-s" + i, symbol, SELL));
            }
            orderIdsBySymbol.add(ids);
        }

        AtomicBoolean writersDone = new AtomicBoolean();
        Future<List<Long>> reader = threads.submit(() -> readContinuously(writersDone));
        List<Future<?>> workers = new ArrayList<>();
        for (int s = 0; s < SYMBOLS.size(); s++) {
            String symbol = SYMBOLS.get(s);
            List<Long> ids = orderIdsBySymbol.get(s);
            workers.add(threads.submit(() -> processInOrder(symbol, ids)));
        }
        for (Future<?> w : workers) {
            w.get(60, TimeUnit.SECONDS);
        }
        writersDone.set(true);
        List<Long> received = reader.get(30, TimeUnit.SECONDS);

        List<Long> stored = jdbc.queryForList(
                "SELECT id FROM notifications WHERE account_id = ? ORDER BY id", Long.class, WATCHED);
        // 계좌 1: 종목마다 매수 대기 등록 알림 1건 + 체결 알림 1건
        assertThat(stored).hasSize(SYMBOLS.size() * PAIRS_PER_SYMBOL * 2);
        List<Long> missing = stored.stream().filter(id -> !received.contains(id)).toList();
        assertThat(missing).as("이어서 읽다가 놓친 알림 번호").isEmpty();
        assertThat(received).as("중복 없이 번호 순서로 받는다").isEqualTo(stored);
    }

    /** 종목 워커 하나를 흉내 낸다: 계획 → T2 커밋 → 호가창 반영. */
    void processInOrder(String symbol, List<Long> orderIds) {
        OrderBook book = new OrderBook();
        long seq = 1;
        for (long id : orderIds) {
            MatchPlan plan = book.plan(incoming(id), seq);
            assertThat(confirm.confirm(symbol, plan)).isTrue();
            book.apply(plan);
            seq++;
        }
    }

    /**
     * 클라이언트처럼 마지막으로 받은 번호 다음부터 계속 읽는다.
     * 모든 쓰기가 커밋된 뒤에 시작한 조회가 빈 결과를 돌려주면 멈춘다.
     */
    List<Long> readContinuously(AtomicBoolean writersDone) {
        List<Long> received = new ArrayList<>();
        long cursor = 0;
        while (true) {
            boolean doneBeforeRead = writersDone.get();
            List<Long> page = jdbc.queryForList(
                    "SELECT id FROM notifications WHERE account_id = ? AND id > ? ORDER BY id LIMIT ?",
                    Long.class, WATCHED, cursor, PAGE);
            if (page.isEmpty() && doneBeforeRead) {
                return received;
            }
            received.addAll(page);
            if (!page.isEmpty()) {
                cursor = page.getLast();
            }
        }
    }

    long place(long account, String clientOrderId, String symbol, Side side) {
        return intake.receive(account, new PlaceOrderRequest(clientOrderId, symbol, side, 1_000, 1));
    }

    IncomingOrder incoming(long orderId) {
        Order o = orders.findById(orderId).orElseThrow();
        return new IncomingOrder(o.id(), o.accountId(), o.side(), o.limitPrice(), o.quantity());
    }
}
