package com.example.stocksinglewas.matching.engine;

import static com.example.stocksinglewas.order.entity.Side.BUY;
import static com.example.stocksinglewas.order.entity.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.health.contributor.Status;

import com.example.stocksinglewas.global.config.MatchingProperties;
import com.example.stocksinglewas.global.config.RecoveryProperties;
import com.example.stocksinglewas.matching.service.MatchConfirmService;
import com.example.stocksinglewas.notification.service.NotificationPushService;
import com.example.stocksinglewas.order.dao.OrderDao;
import com.example.stocksinglewas.order.entity.Order;
import com.example.stocksinglewas.order.entity.OrderStatus;
import com.example.stocksinglewas.order.entity.Side;
import com.example.stocksinglewas.symbol.dao.SymbolDao;
import com.example.stocksinglewas.symbol.entity.Symbol;
import com.example.stocksinglewas.symbol.entity.SymbolStatus;

/**
 * 종목체결준비기: 기동 복구(ADR 0008, 0013). 대기 주문을 정해진 건수씩 이어서 읽어 호가창을 만들고,
 * 모든 종목이 준비되기 전에는 readiness가 열리지 않는다. 순서·조회 규칙이므로 DAO는 mock으로 대신한다.
 */
class SymbolPreparerTest {

    static final MatchingProperties MATCHING =
            new MatchingProperties(100, 10, Duration.ofMillis(10), 2, Duration.ZERO, true);
    static final RecoveryProperties RECOVERY = new RecoveryProperties(2, Duration.ofSeconds(5));

    OrderDao orders;
    SymbolDao symbols;
    SymbolPreparer preparer;

    @BeforeEach
    void setUp() {
        orders = mock(OrderDao.class);
        symbols = mock(SymbolDao.class);
        when(orders.findReceivedIds(anyString(), anyInt())).thenReturn(List.of());
        preparer = new SymbolPreparer(orders, symbols, mock(MatchConfirmService.class),
                mock(NotificationPushService.class), new OrderDispatcher(), MATCHING, RECOVERY,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @AfterEach
    void stop() {
        preparer.stopAll();
    }

    static Order resting(long id, Side side, long price, long seq) {
        return new Order(id, 100 + id, "AAA", "c" + id, side, price, 5, 5, OrderStatus.OPEN, seq, null);
    }

    // --- 묶음 복원 ---

    @Test
    void 대기_주문을_상태별로_묶음_크기씩_마지막_주문번호_다음부터_끝까지_읽는다() {
        when(orders.findRestingAfter("AAA", OrderStatus.OPEN, 0, 2)).thenReturn(List.of(resting(1, BUY, 900, 1), resting(2, SELL, 1100, 2)));
        when(orders.findRestingAfter("AAA", OrderStatus.OPEN, 2, 2)).thenReturn(List.of(resting(5, BUY, 950, 5)));
        when(orders.findRestingAfter("AAA", OrderStatus.PARTIALLY_FILLED, 0, 2)).thenReturn(List.of(resting(3, SELL, 1050, 3), resting(7, BUY, 800, 7)));
        when(orders.findRestingAfter("AAA", OrderStatus.PARTIALLY_FILLED, 7, 2)).thenReturn(List.of());
        when(orders.maxSeq("AAA")).thenReturn(9L);

        preparer.prepare("AAA");

        InOrder order = inOrder(orders);
        order.verify(orders).findRestingAfter("AAA", OrderStatus.OPEN, 0, 2);
        order.verify(orders).findRestingAfter("AAA", OrderStatus.OPEN, 2, 2);
        order.verify(orders).findRestingAfter("AAA", OrderStatus.PARTIALLY_FILLED, 0, 2);
        order.verify(orders).findRestingAfter("AAA", OrderStatus.PARTIALLY_FILLED, 7, 2);
        assertThat(preparer.status("AAA")).hasValueSatisfying(s -> {
            assertThat(s.restoredOrders()).isEqualTo(5);
            assertThat(s.nextSeq()).isEqualTo(10);
        });
    }

    @Test
    void 마지막_묶음이_꽉_차면_빈_결과가_나올_때까지_한_번_더_읽는다() {
        when(orders.findRestingAfter("AAA", OrderStatus.OPEN, 0, 2)).thenReturn(List.of(resting(1, BUY, 900, 1), resting(2, SELL, 1100, 2)));
        when(orders.findRestingAfter("AAA", OrderStatus.OPEN, 2, 2)).thenReturn(List.of());
        when(orders.findRestingAfter("AAA", OrderStatus.PARTIALLY_FILLED, 0, 2)).thenReturn(List.of());

        preparer.prepare("AAA");

        assertThat(preparer.status("AAA")).hasValueSatisfying(s -> assertThat(s.restoredOrders()).isEqualTo(2));
    }

    // --- 실행 중 종목 추가(ADR 0014) ---

    @Test
    void 엔진이_없을_때만_준비하고_이미_있으면_다시_만들지_않는다() {
        when(orders.findRestingAfter(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), anyInt())).thenReturn(List.of());

        preparer.prepareIfAbsent("NEW1");
        preparer.prepareIfAbsent("NEW1");

        org.mockito.Mockito.verify(orders, org.mockito.Mockito.times(1)).maxSeq("NEW1");
        assertThat(preparer.symbols()).containsExactly("NEW1");
    }

    @Test
    void 기동할_때_준비중으로_남은_종목은_엔진을_만들지_않는다() {
        when(symbols.findActive()).thenReturn(List.of(new Symbol("AAA", "A", SymbolStatus.ACTIVE)));
        when(symbols.findAll()).thenReturn(List.of(
                new Symbol("AAA", "A", SymbolStatus.ACTIVE), new Symbol("NEW1", "N", SymbolStatus.PREPARING)));
        when(orders.findRestingAfter(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), anyInt())).thenReturn(List.of());

        preparer.prepareActiveSymbols();

        assertThat(preparer.symbols()).containsExactly("AAA");
        assertThat(preparer.status("NEW1")).isEmpty();
    }

    // --- readiness ---

    @Test
    void 기동_복구가_끝나기_전에는_readiness가_내려가_있다() {
        assertThat(preparer.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    void 활성_종목을_모두_준비하면_readiness가_올라간다() {
        when(symbols.findActive()).thenReturn(List.of(
                new Symbol("AAA", "A", SymbolStatus.ACTIVE), new Symbol("BBB", "B", SymbolStatus.ACTIVE)));
        when(orders.findRestingAfter(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), anyInt())).thenReturn(List.of());

        preparer.prepareActiveSymbols();

        assertThat(preparer.health().getStatus()).isEqualTo(Status.UP);
        assertThat(preparer.health().getDetails()).containsEntry("symbols", 2);
    }

    @Test
    void 종목_준비가_실패하면_readiness를_열지_않고_기동을_실패시킨다() {
        when(symbols.findActive()).thenReturn(List.of(new Symbol("AAA", "A", SymbolStatus.ACTIVE)));
        when(orders.findRestingAfter(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), anyInt()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> preparer.prepareActiveSymbols())
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(preparer.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }
}
