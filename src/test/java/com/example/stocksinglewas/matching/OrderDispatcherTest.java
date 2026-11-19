package com.example.stocksinglewas.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.junit.jupiter.api.Test;

/** 종목별주문전달기: 커밋된 주문번호를 종목 입력 대기열에 넣는다. */
class OrderDispatcherTest {

    final OrderDispatcher dispatcher = new OrderDispatcher();

    @Test
    void putsOrderIdIntoRegisteredSymbolQueue() {
        BlockingQueue<Long> queue = new ArrayBlockingQueue<>(10);
        dispatcher.register("AAA", queue);

        assertThat(dispatcher.dispatch(7L, "AAA")).isTrue();
        assertThat(queue).containsExactly(7L);
    }

    @Test
    void keepsQueuesSeparatePerSymbol() {
        BlockingQueue<Long> a = new ArrayBlockingQueue<>(10);
        BlockingQueue<Long> b = new ArrayBlockingQueue<>(10);
        dispatcher.register("AAA", a);
        dispatcher.register("BBB", b);

        dispatcher.dispatch(1L, "BBB");

        assertThat(a).isEmpty();
        assertThat(b).containsExactly(1L);
    }

    @Test
    void returnsFalseWhenQueueIsFull() {
        BlockingQueue<Long> queue = new ArrayBlockingQueue<>(1);
        dispatcher.register("AAA", queue);
        dispatcher.dispatch(1L, "AAA");

        assertThat(dispatcher.dispatch(2L, "AAA")).isFalse();
        assertThat(queue).containsExactly(1L);
    }

    @Test
    void returnsFalseWhenSymbolHasNoQueue() {
        assertThat(dispatcher.dispatch(1L, "AAA")).isFalse();
    }
}
