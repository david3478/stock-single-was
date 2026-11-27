package com.example.stocksinglewas.matching.engine;

import static com.example.stocksinglewas.order.entity.Side.BUY;
import static com.example.stocksinglewas.order.entity.Side.SELL;
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

import com.example.stocksinglewas.global.config.MatchingProperties;
import com.example.stocksinglewas.order.dao.OrderDao;
import com.example.stocksinglewas.order.entity.Order;
import com.example.stocksinglewas.order.entity.OrderStatus;
import com.example.stocksinglewas.order.entity.Side;
import com.example.stocksinglewas.matching.engine.SymbolWorker.Outcome;
import com.example.stocksinglewas.matching.service.MatchConfirmService;
import com.example.stocksinglewas.notification.service.NotificationPushService;

/**
 * 종목체결워커: 주문을 읽어 계획 → T2 커밋 → 커밋 성공 후에만 호가창 반영.
 * DB가 결과를 정하지 않는 순서·실패 처리 규칙이므로 DAO와 체결확정Service를 mock으로 대신한다.
 */
class SymbolWorkerTest {

    static final MatchingProperties PROPS =
            new MatchingProperties(100, 10, Duration.ofMillis(10), 2, Duration.ZERO, false);

    OrderDao orders;
    MatchConfirmService confirm;
    NotificationPushService push;
    SymbolQueue queue;
    OrderBook book;
    SymbolWorker worker;

    @BeforeEach
    void setUp() {
        orders = mock(OrderDao.class);
        confirm = mock(MatchConfirmService.class);
        push = mock(NotificationPushService.class);
        queue = new SymbolQueue(PROPS.queueCapacity());
        book = new OrderBook();
        worker = new SymbolWorker("AAA", queue, book, 10, orders, confirm, push, PROPS);
        when(confirm.confirm(anyString(), any())).thenReturn(true);
    }

    void received(long id, long account, Side side, long price, int qty) {
        when(orders.findById(id)).thenReturn(Optional.of(new Order(id, account, "AAA", "c" + id, side, price,
                qty, qty, OrderStatus.RECEIVED, null, null)));
    }

    // --- 성공 ---

    @Test
    void 상대가_없으면_커밋_후_다음_처리순번으로_대기_등록된다() {
        received(1, 100, BUY, 1_000, 5);

        assertThat(worker.process(1)).isEqualTo(Outcome.APPLIED);

        assertThat(book.orders(BUY)).containsExactly(new RestingOrder(1, 100, BUY, 1_000, 5, 10));
        assertThat(worker.nextSeq()).isEqualTo(11);
    }

    @Test
    void 체결된_주문은_커밋_후_호가창에_반영된다() {
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
    void 데드락이_나면_T2_전체를_다시_시도한다() {
        received(1, 100, BUY, 1_000, 5);
        when(confirm.confirm(anyString(), any()))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(true);

        assertThat(worker.process(1)).isEqualTo(Outcome.APPLIED);

        verify(confirm, times(2)).confirm(anyString(), any());
        assertThat(book.orders(BUY)).hasSize(1);
    }

    @Test
    void 커밋_후_관련_계좌들에_알림_신호를_보낸다() {
        book.add(new RestingOrder(1, 100, SELL, 1_000, 5, 3));
        received(2, 200, BUY, 1_000, 5);

        worker.process(2);

        verify(push).signal(List.of(100L, 200L));
    }

    // --- 건너뛰기: 중복 전달 ---

    @Test
    void 이미_처리된_주문이_다시_오면_건너뛴다() {
        when(orders.findById(1L)).thenReturn(Optional.of(new Order(1L, 100, "AAA", "c1", BUY, 1_000,
                5, 5, OrderStatus.OPEN, 3L, null)));

        assertThat(worker.process(1)).isEqualTo(Outcome.SKIPPED);

        verify(confirm, never()).confirm(anyString(), any());
        assertThat(worker.nextSeq()).isEqualTo(10);
    }

    @Test
    void 없는_주문번호는_건너뛴다() {
        when(orders.findById(1L)).thenReturn(Optional.empty());

        assertThat(worker.process(1)).isEqualTo(Outcome.SKIPPED);
    }

    @Test
    void 커밋_시점에_이미_처리된_주문이면_호가창을_건드리지_않는다() {
        received(1, 100, BUY, 1_000, 5);
        when(confirm.confirm(anyString(), any())).thenReturn(false);

        assertThat(worker.process(1)).isEqualTo(Outcome.SKIPPED);

        assertThat(book.orders(BUY)).isEmpty();
        assertThat(worker.nextSeq()).isEqualTo(10);
        verify(push, never()).signal(any());
    }

    // --- 실패: 호가창과 순번은 그대로 ---

    @Test
    void 커밋이_실패하면_호가창과_처리순번이_그대로다() {
        book.add(new RestingOrder(1, 100, SELL, 1_000, 5, 3));
        List<RestingOrder> before = book.orders(SELL);
        received(2, 200, BUY, 1_000, 5);
        when(confirm.confirm(anyString(), any())).thenThrow(new IllegalStateException("db mismatch"));

        assertThatThrownBy(() -> worker.process(2)).isInstanceOf(IllegalStateException.class);

        assertThat(book.orders(SELL)).isEqualTo(before);
        assertThat(worker.nextSeq()).isEqualTo(10);
        verify(push, never()).signal(any());
    }

    @Test
    void 데드락_재시도_횟수를_넘기면_포기하고_호가창은_그대로다() {
        received(1, 100, BUY, 1_000, 5);
        when(confirm.confirm(anyString(), any())).thenThrow(new CannotAcquireLockException("deadlock"));

        assertThatThrownBy(() -> worker.process(1)).isInstanceOf(CannotAcquireLockException.class);

        verify(confirm, times(1 + PROPS.deadlockMaxRetries())).confirm(anyString(), any());
        assertThat(book.orders(BUY)).isEmpty();
    }

    // --- 대기열 넘침: DB의 RECEIVED에서 이어 처리 (ADR 0005 ①) ---

    @Test
    void 넘침_중에는_DB의_미처리_주문을_처리하고_다_비면_넘침을_해제한다() {
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
    void 넘침_해제_중에_DB에_새_주문이_보이면_넘침을_유지한다() {
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
