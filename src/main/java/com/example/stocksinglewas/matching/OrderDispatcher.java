package com.example.stocksinglewas.matching;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 종목별주문전달기. 접수가 커밋된 주문번호를 해당 종목의 입력 대기열에 넣는다.
 * 대기열은 종목체결준비기({@link SymbolPreparer})가 엔진을 준비할 때 등록한다.
 *
 * <p>넣지 못해도 주문은 DB에 RECEIVED로 남아 있고, 워커가 넘침 처리로 DB에서 이어 처리한다.
 */
@Component
public class OrderDispatcher {

    private final Map<String, SymbolQueue> queues = new ConcurrentHashMap<>();

    /** 대기열등록. */
    public void register(String symbolCode, SymbolQueue queue) {
        queues.put(symbolCode, queue);
    }

    public void unregister(String symbolCode) {
        queues.remove(symbolCode);
    }

    /** 전달. 대기열이 넘쳤거나 종목 대기열이 없으면 false. */
    public boolean dispatch(long orderId, String symbolCode) {
        SymbolQueue queue = queues.get(symbolCode);
        return queue != null && queue.offer(orderId);
    }
}
