package com.example.stocksinglewas.notification.service;

import static com.example.stocksinglewas.order.entity.Side.BUY;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.stocksinglewas.matching.engine.IncomingOrder;
import com.example.stocksinglewas.matching.engine.MatchPlan;
import com.example.stocksinglewas.matching.engine.OrderBook;
import com.example.stocksinglewas.matching.service.MatchConfirmService;
import com.example.stocksinglewas.order.dao.OrderDao;
import com.example.stocksinglewas.order.dto.PlaceOrderRequest;
import com.example.stocksinglewas.order.entity.Order;
import com.example.stocksinglewas.order.service.OrderIntakeService;
import com.example.stocksinglewas.support.MySqlIntegrationTest;
import com.example.stocksinglewas.support.TestData;

/**
 * ADR 0003 검증 1(결정적 재현). 클라이언트는 {@code id > 마지막ID}로 이어서 받으므로,
 * 같은 계좌에서 번호를 먼저 받은 알림이 나중에 커밋되면 그 알림을 영구히 놓친다.
 *
 * <p>시나리오는 두 T2가 모두 대기 등록인 경우다. 대기 등록은 계좌 행을 UPDATE하지 않는다.
 * 알림 INSERT의 외래키 검사가 계좌 행에 공유 잠금(S)을 걸지만 S끼리는 서로 막지 않으므로,
 * T2 시작 때의 명시적 계좌 잠금(FOR UPDATE)이 없으면 두 T2가 동시에 번호를 받는다(ADR 0003 ③).
 * 체결이 있는 T2는 정산 UPDATE가 상대의 S 잠금에 막히므로 이 테스트의 대상이 아니다.
 *
 * <p>"기다린다"는 시간으로 짐작하지 않고 {@code performance_schema.data_lock_waits}로 확인한다.
 * {@code information_schema.innodb_trx}는 캐시되어 짧은 간격으로 읽으면 갱신되지 않을 수 있다.
 */
class NotificationCursorTest extends MySqlIntegrationTest {

    static final long SHARED = 1;
    static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    MatchConfirmService confirm;

    @Autowired
    NotificationWriter writer;

    @Autowired
    OrderIntakeService intake;

    @Autowired
    OrderDao orders;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    TestData db;
    ExecutorService threads;

    @BeforeEach
    void seed() {
        db = new TestData(jdbc);
        db.clear();
        db.symbol("XXX");
        db.symbol("YYY");
        db.account(SHARED, 100_000);
        threads = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @Test
    void 같은_계좌의_대기_등록_T2는_앞_T2가_커밋할_때까지_알림_번호를_받지_못한다() throws Exception {
        long first = place("a", "XXX");
        long second = place("b", "YYY");
        MatchPlan planA = new OrderBook().plan(incoming(first), 1);
        MatchPlan planB = new OrderBook().plan(incoming(second), 1);

        CountDownLatch aInserted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        Future<?> a = threads.submit(() -> tx.executeWithoutResult(s -> {
            confirm.confirm("XXX", planA);
            aInserted.countDown();
            await(releaseA);
        }));
        assertThat(aInserted.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        Future<?> b = threads.submit(() -> confirm.confirm("YYY", planB));
        assertThat(waitUntilLockWait(b)).as("B는 계좌 1 잠금에서 기다려야 한다").isTrue();

        releaseA.countDown();
        a.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        b.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(notificationOrders()).containsExactly(first, second);
    }

    @Test
    void 잠금이_없으면_커서가_먼저_번호를_받은_알림을_놓친다() throws Exception {
        long first = place("a", "XXX");
        long second = place("b", "YYY");

        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Future<?> a = threads.submit(() -> tx.executeWithoutResult(s -> {
            writer.recordOpen(SHARED, first, 5);
            firstInserted.countDown();
            await(releaseFirst);
        }));
        assertThat(firstInserted.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        tx.executeWithoutResult(s -> writer.recordOpen(SHARED, second, 5));

        long cursor = jdbc.queryForObject(
                "SELECT MAX(id) FROM notifications WHERE account_id = ?", Long.class, SHARED);
        releaseFirst.countDown();
        a.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        List<Long> afterCursor = jdbc.queryForList(
                "SELECT order_id FROM notifications WHERE account_id = ? AND id > ?", Long.class, SHARED, cursor);
        assertThat(afterCursor).as("먼저 번호를 받고 늦게 커밋된 알림이 커서 뒤에 없다").isEmpty();
        assertThat(notificationOrders()).containsExactly(first, second);
    }

    long place(String clientOrderId, String symbol) {
        return intake.receive(SHARED, new PlaceOrderRequest(clientOrderId, symbol, BUY, 1_000, 5));
    }

    IncomingOrder incoming(long orderId) {
        Order o = orders.findById(orderId).orElseThrow();
        return new IncomingOrder(o.id(), o.accountId(), o.side(), o.limitPrice(), o.quantity());
    }

    /** 알림 번호 순으로 계좌 1 알림의 주문번호. */
    List<Long> notificationOrders() {
        return jdbc.queryForList(
                "SELECT order_id FROM notifications WHERE account_id = ? ORDER BY id", Long.class, SHARED);
    }

    /**
     * 다른 트랜잭션이 잠금 대기에 들어갈 때까지 기다린다. 대기 없이 먼저 끝나면 false.
     * performance_schema 조회 권한이 필요해 root로 따로 연결한다.
     */
    static boolean waitUntilLockWait(Future<?> other) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement st = root.createStatement()) {
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM performance_schema.data_lock_waits")) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return true;
                    }
                }
                if (other.isDone()) {
                    return false;
                }
                Thread.sleep(10);
            }
        }
        return false;
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
