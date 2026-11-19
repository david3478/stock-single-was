package com.example.stocksinglewas.matching;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 종목별주문전달기. 접수가 커밋된 주문번호를 해당 종목의 입력 대기열에 넣는다.
 * 대기열은 종목 엔진을 준비할 때 등록한다(4단계).
 *
 * <p>넣지 못해도 주문은 DB에 RECEIVED로 남아 있으므로 잃어버리지 않는다.
 * 넣지 못한 주문을 다시 처리하는 경로는 4단계의 대기열 넘침 처리(ADR 0005 ①)다.
 */
@Component
public class OrderDispatcher {

    private final Map<String, BlockingQueue<Long>> queues = new ConcurrentHashMap<>();

    /** 대기열등록. */
    public void register(String symbolCode, BlockingQueue<Long> queue) {
        queues.put(symbolCode, queue);
    }

    /** 전달. 대기열이 가득 찼거나 종목 대기열이 없으면 false. */
    public boolean dispatch(long orderId, String symbolCode) {
        BlockingQueue<Long> queue = queues.get(symbolCode);
        return queue != null && queue.offer(orderId);
    }
}
