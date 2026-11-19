package com.example.stocksinglewas.matching;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 종목입력대기열. 접수가 커밋된 주문번호를 담는 상한 있는 큐와 넘침 표시(ADR 0005 ①).
 *
 * <p>큐에 넣지 못하면 넘침 상태가 된다. 넘침 중에는 새 주문도 큐에 넣지 않고 DB(RECEIVED)에 남긴다.
 * 워커가 DB에서 RECEIVED를 ID 순으로 읽어 처리하고, 남은 것이 없을 때 넘침을 해제한다.
 */
public final class SymbolQueue {

    private final BlockingQueue<Long> orderIds;
    private final AtomicBoolean overflow = new AtomicBoolean();

    public SymbolQueue(int capacity) {
        this.orderIds = new ArrayBlockingQueue<>(capacity);
    }

    /** 넣기시도. 넘침 중이거나 가득 차면 false이고 넘침 상태가 된다. */
    public boolean offer(long orderId) {
        if (overflow.get()) {
            return false;
        }
        if (orderIds.offer(orderId)) {
            return true;
        }
        overflow.set(true);
        return false;
    }

    /** 꺼내기. 시간 안에 없으면 null. */
    public Long poll(Duration timeout) throws InterruptedException {
        return orderIds.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    public boolean overflowed() {
        return overflow.get();
    }

    public void markOverflow() {
        overflow.set(true);
    }

    public void clearOverflow() {
        overflow.set(false);
    }
}
