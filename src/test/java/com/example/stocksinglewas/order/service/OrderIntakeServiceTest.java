package com.example.stocksinglewas.order.service;

import static com.example.stocksinglewas.order.entity.Side.BUY;
import static com.example.stocksinglewas.order.entity.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.stocksinglewas.global.exception.OrderRejectedException;
import com.example.stocksinglewas.order.dto.PlaceOrderRequest;
import com.example.stocksinglewas.order.entity.Side;
import com.example.stocksinglewas.matching.engine.OrderDispatcher;
import com.example.stocksinglewas.global.exception.OrderRejectedException.Reason;
import com.example.stocksinglewas.support.MySqlIntegrationTest;

/**
 * 주문접수Service.접수: 예약과 RECEIVED 주문 저장이 한 트랜잭션(T1)이고, 커밋 후에만 대기열로 전달한다.
 * 잔고 판단과 동시 접수는 DB의 조건부 UPDATE와 잠금이 정하므로 실제 MySQL로 검증한다.
 */
class OrderIntakeServiceTest extends MySqlIntegrationTest {

    static final long ACCOUNT = 1;
    static final long OTHER_ACCOUNT = 2;
    static final long CASH = 100_000;
    static final int HELD = 10;

    @Autowired
    OrderIntakeService intake;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    OrderDispatcher dispatcher;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM trades");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM holdings");
        jdbc.update("DELETE FROM accounts");
        jdbc.update("DELETE FROM symbols");
        jdbc.update("INSERT INTO symbols (code, name, status) VALUES ('AAA', '활성종목', 'ACTIVE')");
        jdbc.update("INSERT INTO symbols (code, name, status) VALUES ('NEW', '준비종목', 'PREPARING')");
        jdbc.update("INSERT INTO accounts (id, cash_balance, reserved_cash) VALUES (?, ?, 0), (?, ?, 0)",
                ACCOUNT, CASH, OTHER_ACCOUNT, CASH);
        jdbc.update("INSERT INTO holdings (account_id, symbol_code, quantity, reserved_quantity) VALUES (?, 'AAA', ?, 0)",
                ACCOUNT, HELD);
        when(dispatcher.dispatch(anyLong(), anyString())).thenReturn(true);
    }

    static PlaceOrderRequest order(String clientOrderId, Side side, long price, int quantity) {
        return new PlaceOrderRequest(clientOrderId, "AAA", side, price, quantity);
    }

    long cash(long account) {
        return jdbc.queryForObject("SELECT cash_balance FROM accounts WHERE id = ?", Long.class, account);
    }

    long reservedCash(long account) {
        return jdbc.queryForObject("SELECT reserved_cash FROM accounts WHERE id = ?", Long.class, account);
    }

    int reservedQuantity(long account) {
        return jdbc.queryForObject(
                "SELECT reserved_quantity FROM holdings WHERE account_id = ? AND symbol_code = 'AAA'",
                Integer.class, account);
    }

    int orderCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
    }

    // --- 성공 ---

    @Test
    void 매수는_현금을_예약하고_처리_전_주문을_저장한다() {
        long orderId = intake.receive(ACCOUNT, order("c1", BUY, 1_000, 30));

        assertThat(cash(ACCOUNT)).isEqualTo(CASH - 30_000);
        assertThat(reservedCash(ACCOUNT)).isEqualTo(30_000);
        assertThat(jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId))
                .containsEntry("status", "RECEIVED")
                .containsEntry("side", "BUY")
                .containsEntry("qty", 30)
                .containsEntry("remaining", 30)
                .containsEntry("seq", null);
    }

    @Test
    void 매도는_현금을_건드리지_않고_수량을_예약한다() {
        intake.receive(ACCOUNT, order("c1", SELL, 1_000, 4));

        assertThat(reservedQuantity(ACCOUNT)).isEqualTo(4);
        assertThat(cash(ACCOUNT)).isEqualTo(CASH);
        assertThat(reservedCash(ACCOUNT)).isZero();
    }

    @Test
    void 커밋_후_종목_대기열로_전달한다() {
        long orderId = intake.receive(ACCOUNT, order("c1", BUY, 1_000, 1));

        verify(dispatcher).dispatch(orderId, "AAA");
    }

    @Test
    void 대기열이_가득_차도_접수는_성공한다() {
        when(dispatcher.dispatch(anyLong(), anyString())).thenReturn(false);

        long orderId = intake.receive(ACCOUNT, order("c1", BUY, 1_000, 1));

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("RECEIVED");
    }

    // --- 같은 요청번호로 다시 요청 ---

    @Test
    void 같은_요청을_다시_보내면_기존_주문을_돌려주고_두_번_예약하지_않는다() {
        long first = intake.receive(ACCOUNT, order("c1", BUY, 1_000, 30));
        long second = intake.receive(ACCOUNT, order("c1", BUY, 1_000, 30));

        assertThat(second).isEqualTo(first);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(reservedCash(ACCOUNT)).isEqualTo(30_000);
    }

    @Test
    void 응답을_못_받아_다시_보낸_요청은_아직_처리_전이면_대기열로_다시_전달한다() {
        // 커밋 결과 불명(ADR 0005 ②, 0013): 커밋은 됐지만 전달 전에 연결이 끊겨 클라이언트가 같은 요청을 다시 보낸다
        when(dispatcher.dispatch(anyLong(), anyString())).thenReturn(false);
        long first = intake.receive(ACCOUNT, order("c1", BUY, 1_000, 30));
        when(dispatcher.dispatch(anyLong(), anyString())).thenReturn(true);

        long retried = intake.receive(ACCOUNT, order("c1", BUY, 1_000, 30));

        assertThat(retried).isEqualTo(first);
        verify(dispatcher, times(2)).dispatch(first, "AAA");
    }

    @Test
    void 다시_보낸_요청의_주문이_이미_처리됐으면_다시_전달하지_않는다() {
        long first = intake.receive(ACCOUNT, order("c1", BUY, 1_000, 30));
        jdbc.update("UPDATE orders SET status = 'OPEN', seq = 1 WHERE id = ?", first);

        intake.receive(ACCOUNT, order("c1", BUY, 1_000, 30));

        verify(dispatcher, times(1)).dispatch(first, "AAA");
    }

    @Test
    void 계좌가_다르면_같은_요청번호도_다른_주문이다() {
        jdbc.update("UPDATE accounts SET cash_balance = ? WHERE id = ?", CASH, OTHER_ACCOUNT);

        long a = intake.receive(ACCOUNT, order("c1", BUY, 1_000, 1));
        long b = intake.receive(OTHER_ACCOUNT, order("c1", BUY, 1_000, 1));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 같은_요청번호로_내용이_다른_주문은_거절한다() {
        intake.receive(ACCOUNT, order("c1", BUY, 1_000, 30));

        assertRejected(() -> intake.receive(ACCOUNT, order("c1", BUY, 1_000, 31)), Reason.REQUEST_ID_CONFLICT);
        assertThat(reservedCash(ACCOUNT)).isEqualTo(30_000);
    }

    // --- 거절: 아무것도 저장하거나 예약하지 않는다 ---

    @Test
    void 현금이_부족하면_매수를_거절한다() {
        assertRejected(() -> intake.receive(ACCOUNT, order("c1", BUY, 1_000, 101)), Reason.INSUFFICIENT_CASH);

        assertThat(cash(ACCOUNT)).isEqualTo(CASH);
        assertThat(orderCount()).isZero();
        verify(dispatcher, never()).dispatch(anyLong(), anyString());
    }

    @Test
    void 현금을_딱_맞게_다_쓰는_매수는_접수된다() {
        intake.receive(ACCOUNT, order("c1", BUY, 1_000, 100));

        assertThat(cash(ACCOUNT)).isZero();
    }

    @Test
    void 매도_가능_수량이_부족하면_매도를_거절한다() {
        intake.receive(ACCOUNT, order("c1", SELL, 1_000, 7));

        assertRejected(() -> intake.receive(ACCOUNT, order("c2", SELL, 1_000, 4)), Reason.INSUFFICIENT_QUANTITY);
        assertThat(reservedQuantity(ACCOUNT)).isEqualTo(7);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void 보유하지_않은_종목은_매도할_수_없다() {
        assertRejected(() -> intake.receive(OTHER_ACCOUNT, order("c1", SELL, 1_000, 1)),
                Reason.INSUFFICIENT_QUANTITY);
    }

    @Test
    void 없는_계좌는_거절한다() {
        assertRejected(() -> intake.receive(999, order("c1", BUY, 1_000, 1)), Reason.ACCOUNT_NOT_FOUND);
    }

    @Test
    void 거래_가능하지_않은_종목은_거절한다() {
        assertRejected(() -> intake.receive(ACCOUNT, new PlaceOrderRequest("c1", "NEW", BUY, 1_000, 1)),
                Reason.SYMBOL_NOT_TRADABLE);
        assertRejected(() -> intake.receive(ACCOUNT, new PlaceOrderRequest("c2", "NONE", BUY, 1_000, 1)),
                Reason.SYMBOL_NOT_TRADABLE);
        assertThat(orderCount()).isZero();
    }

    @Test
    void 주문_금액이_범위를_넘으면_거절한다() {
        assertRejected(() -> intake.receive(ACCOUNT, order("c1", BUY, Long.MAX_VALUE / 2, 3)),
                Reason.AMOUNT_OUT_OF_RANGE);
        assertThat(reservedCash(ACCOUNT)).isZero();
    }

    // --- 동시 접수 ---

    @Test
    void 보유량_전부를_동시에_매도하면_한_건만_성공한다() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String clientOrderId = "s" + i;
                Callable<Boolean> task = () -> {
                    start.await();
                    try {
                        intake.receive(ACCOUNT, order(clientOrderId, SELL, 1_000, HELD));
                        return true;
                    } catch (OrderRejectedException e) {
                        return false;
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> r : results) {
                succeeded += r.get() ? 1 : 0;
            }

            assertThat(succeeded).isEqualTo(1);
            assertThat(reservedQuantity(ACCOUNT)).isEqualTo(HELD);
            assertThat(orderCount()).isEqualTo(1);
            verify(dispatcher, times(1)).dispatch(anyLong(), anyString());
        } finally {
            pool.shutdownNow();
        }
    }

    static void assertRejected(Runnable call, Reason reason) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(OrderRejectedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
