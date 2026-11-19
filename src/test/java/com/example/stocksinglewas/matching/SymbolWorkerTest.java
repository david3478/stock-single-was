package com.example.stocksinglewas.matching;

import static com.example.stocksinglewas.entity.Side.BUY;
import static com.example.stocksinglewas.entity.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;

import com.example.stocksinglewas.config.MatchingProperties;
import com.example.stocksinglewas.dao.OrderDao;
import com.example.stocksinglewas.entity.Order;
import com.example.stocksinglewas.entity.OrderStatus;
import com.example.stocksinglewas.entity.Side;
import com.example.stocksinglewas.matching.SymbolWorker.Outcome;
import com.example.stocksinglewas.service.MatchConfirmService;

/**
 * 종목체결워커: 주문을 읽어 계획 → T2 커밋 → 커밋 성공 후에만 호가창 반영.
 * DB가 결과를 정하지 않는 순서·실패 처리 규칙이므로 DAO와 체결확정Service를 mock으로 대신한다.
 */
class SymbolWorkerTest {

    static final MatchingProperties PROPS =
            new MatchingProperties(100, 10, Duration.ofMillis(10), 2, Duration.ZERO, false);

    OrderDao orders;
    MatchConfirmService confirm;
    SymbolQueue queue;
    OrderBook book;
    SymbolWorker worker;

    @BeforeEach
    void setUp() {
        orders = mock(OrderDao.class);
        confirm = mock(MatchConfirmService.class);
        queue = new SymbolQueue(PROPS.queueCapacity());
        book = new OrderBook();
        worker = new SymbolWorker("AAA", queue, book, 10, orders, confirm, PROPS);
        when(confirm.confirm(anyString(), any())).thenReturn(true);
    }

    void received(long id, long account, Side side, long price, int qty) {
        when(orders.findById(id)).thenReturn(Optional.of(new Order(id, account, "AAA", "c" + id, side, price,
                qty, qty, OrderStatus.RECEIVED, null, null)));
    }

    // --- 성공 ---

    @Test
    void unmatchedOrderRestsWithNextSeqAfterCommit() {
        received(1, 100, BUY, 1_000, 5);

        assertThat(worker.process(1)).isEqualTo(Outcome.APPLIED);

        assertThat(book.orders(BUY)).containsExactly(new RestingOrder(1, 100, BUY, 1_000, 5, 10));
        assertThat(worker.nextSeq()).isEqualTo(11);
    }

    @Test
    void matchedOrderIsAppliedToBookAfterCommit() {
        book.add(new RestingOrder(1, 100, SELL, 1_000, 5, 3));
        received(2, 200, BUY, 1_000, 5);

        worker.process(2);

        ArgumentCaptor<MatchPlan> plan = ArgumentCaptor.forClass(MatchPlan.class);
        verify(confirm).confirm(eq("AAA"), plan.capture());
        assertThat(plan.getValue().fills()).hasSize(1);
        assertThat(plan.getValue().seq()).isEqualTo(10);
        assertThat(book.orders(SELL)).isEmpty();
    }

    @Test
    void retriesWholeCommitAfterDeadlock() {
        received(1, 100, BUY, 1_000, 5);
        when(confirm.confirm(anyString(), any()))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(true);

        assertThat(worker.process(1)).isEqualTo(Outcome.APPLIED);

        verify(confirm, times(2)).confirm(anyString(), any());
        assertThat(book.orders(BUY)).hasSize(1);
    }

    // --- 건너뛰기: 중복 전달 ---

    @Test
    void skipsOrderThatIsNoLongerReceived() {
        when(orders.findById(1L)).thenReturn(Optional.of(new Order(1L, 100, "AAA", "c1", BUY, 1_000,
                5, 5, OrderStatus.OPEN, 3L, null)));

        assertThat(worker.process(1)).isEqualTo(Outcome.SKIPPED);

        verify(confirm, never()).confirm(anyString(), any());
        assertThat(worker.nextSeq()).isEqualTo(10);
    }

    @Test
    void skipsUnknownOrder() {
        when(orders.findById(1L)).thenReturn(Optional.empty());

        assertThat(worker.process(1)).isEqualTo(Outcome.SKIPPED);
    }

    @Test
    void skipsWithoutTouchingBookWhenCommitFindsOrderAlreadyProcessed() {
        received(1, 100, BUY, 1_000, 5);
        when(confirm.confirm(anyString(), any())).thenReturn(false);

        assertThat(worker.process(1)).isEqualTo(Outcome.SKIPPED);

        assertThat(book.orders(BUY)).isEmpty();
        assertThat(worker.nextSeq()).isEqualTo(10);
    }

    // --- 실패: 호가창과 순번은 그대로 ---

    @Test
    void leavesBookAndSeqUnchangedWhenCommitFails() {
        book.add(new RestingOrder(1, 100, SELL, 1_000, 5, 3));
        List<RestingOrder> before = book.orders(SELL);
        received(2, 200, BUY, 1_000, 5);
        when(confirm.confirm(anyString(), any())).thenThrow(new IllegalStateException("db mismatch"));

        assertThatThrownBy(() -> worker.process(2)).isInstanceOf(IllegalStateException.class);

        assertThat(book.orders(SELL)).isEqualTo(before);
        assertThat(worker.nextSeq()).isEqualTo(10);
    }

    @Test
    void givesUpAfterMaxDeadlockRetriesWithBookUnchanged() {
        received(1, 100, BUY, 1_000, 5);
        when(confirm.confirm(anyString(), any())).thenThrow(new CannotAcquireLockException("deadlock"));

        assertThatThrownBy(() -> worker.process(1)).isInstanceOf(CannotAcquireLockException.class);

        verify(confirm, times(1 + PROPS.deadlockMaxRetries())).confirm(anyString(), any());
        assertThat(book.orders(BUY)).isEmpty();
    }

    // --- 대기열 넘침: DB의 RECEIVED에서 이어 처리 (ADR 0005 ①) ---

    @Test
    void drainProcessesReceivedOrdersFromDbAndClearsOverflowWhenEmpty() {
        queue.markOverflow();
        received(1, 100, BUY, 1_000, 5);
        received(2, 101, BUY, 1_000, 5);
        when(orders.findReceivedIds("AAA", PROPS.drainBatchSize()))
                .thenReturn(List.of(1L, 2L))
                .thenReturn(List.of());

        worker.drainFromDb();
        worker.drainFromDb();

        assertThat(book.orders(BUY)).hasSize(2);
        assertThat(queue.overflowed()).isFalse();
    }

    @Test
    void drainKeepsOverflowWhenOrderArrivesWhileClearing() {
        queue.markOverflow();
        received(3, 100, BUY, 1_000, 5);
        when(orders.findReceivedIds("AAA", PROPS.drainBatchSize()))
                .thenReturn(List.of())
                .thenReturn(List.of(3L));

        worker.drainFromDb();

        assertThat(book.orders(BUY)).hasSize(1);
        assertThat(queue.overflowed()).isTrue();
    }
}
