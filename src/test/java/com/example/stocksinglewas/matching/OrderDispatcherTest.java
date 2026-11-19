package com.example.stocksinglewas.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** 종목별주문전달기와 종목입력대기열: 커밋된 주문번호를 종목 대기열에 넣고, 넘치면 넘침 상태가 된다. */
class OrderDispatcherTest {

    final OrderDispatcher dispatcher = new OrderDispatcher();

    @Test
    void putsOrderIdIntoRegisteredSymbolQueue() throws InterruptedException {
        SymbolQueue queue = new SymbolQueue(10);
        dispatcher.register("AAA", queue);

        assertThat(dispatcher.dispatch(7L, "AAA")).isTrue();
        assertThat(queue.poll(Duration.ZERO)).isEqualTo(7L);
    }

    @Test
    void keepsQueuesSeparatePerSymbol() throws InterruptedException {
        SymbolQueue a = new SymbolQueue(10);
        SymbolQueue b = new SymbolQueue(10);
        dispatcher.register("AAA", a);
        dispatcher.register("BBB", b);

        dispatcher.dispatch(1L, "BBB");

        assertThat(a.poll(Duration.ZERO)).isNull();
        assertThat(b.poll(Duration.ZERO)).isEqualTo(1L);
    }

    @Test
    void fullQueueTurnsOnOverflow() {
        SymbolQueue queue = new SymbolQueue(1);
        dispatcher.register("AAA", queue);
        dispatcher.dispatch(1L, "AAA");

        assertThat(dispatcher.dispatch(2L, "AAA")).isFalse();
        assertThat(queue.overflowed()).isTrue();
    }

    /** 넘침 중에는 자리가 나도 새 주문을 큐에 넣지 않는다. DB의 RECEIVED를 ID 순으로 이어 처리해야 순서가 섞이지 않는다. */
    @Test
    void keepsNewOrdersInDbWhileOverflowing() throws InterruptedException {
        SymbolQueue queue = new SymbolQueue(1);
        dispatcher.register("AAA", queue);
        dispatcher.dispatch(1L, "AAA");
        dispatcher.dispatch(2L, "AAA");
        queue.poll(Duration.ZERO);

        assertThat(dispatcher.dispatch(3L, "AAA")).isFalse();
        assertThat(queue.poll(Duration.ZERO)).isNull();
    }

    @Test
    void returnsFalseWhenSymbolHasNoQueue() {
        assertThat(dispatcher.dispatch(1L, "AAA")).isFalse();
    }

    @Test
    void returnsFalseAfterUnregister() {
        dispatcher.register("AAA", new SymbolQueue(10));
        dispatcher.unregister("AAA");

        assertThat(dispatcher.dispatch(1L, "AAA")).isFalse();
    }
}
