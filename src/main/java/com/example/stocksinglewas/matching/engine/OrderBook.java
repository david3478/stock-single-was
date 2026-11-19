package com.example.stocksinglewas.matching.engine;

import com.example.stocksinglewas.order.entity.Side;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * 종목 하나의 대기주문목록(EA: 대기주문목록). 가격-시간(seq) 우선.
 * 해당 종목 워커 한 스레드만 접근하므로 동기화하지 않는다.
 *
 * <p>변경은 두 경로뿐이다. 복구 때 {@link #add}, 처리 때 {@link #plan}으로 계획을 만들고
 * T2 커밋 후 {@link #apply}. plan은 목록을 바꾸지 않으므로 T2가 실패하면 계획을 버리기만 하면 된다.
 */
public final class OrderBook {

    /** 가격 → (seq → 주문). 매수는 높은 가격 먼저. */
    private final NavigableMap<Long, NavigableMap<Long, RestingOrder>> bids = new TreeMap<>(Comparator.reverseOrder());
    /** 매도는 낮은 가격 먼저. */
    private final NavigableMap<Long, NavigableMap<Long, RestingOrder>> asks = new TreeMap<>();
    private final Map<Long, RestingOrder> byOrderId = new HashMap<>();
    private final Set<Long> seqs = new HashSet<>();

    /** 잔여 주문을 등록한다. 복구 때 DB의 가격과 원래 seq로 호출한다. */
    public void add(RestingOrder order) {
        if (byOrderId.containsKey(order.orderId())) {
            throw new IllegalArgumentException("duplicate order: " + order.orderId());
        }
        if (seqs.contains(order.seq())) {
            throw new IllegalArgumentException("duplicate seq: " + order.seq());
        }
        insert(order);
    }

    /** 입력 주문을 처리할 계획을 만든다. 목록은 바꾸지 않는다. */
    public MatchPlan plan(IncomingOrder in, long seq) {
        if (seqs.contains(seq)) {
            throw new IllegalArgumentException("seq already used: " + seq);
        }
        List<Fill> fills = new ArrayList<>();
        List<CounterpartyChange> changes = new ArrayList<>();
        int remaining = in.quantity();

        for (var level : side(in.side().opposite()).entrySet()) {
            if (remaining == 0 || !crosses(in, level.getKey())) {
                break;
            }
            for (RestingOrder resting : level.getValue().values()) {
                int qty = Math.min(remaining, resting.remaining());
                fills.add(fill(in, resting, qty));
                changes.add(new CounterpartyChange(resting, resting.remaining() - qty));
                remaining -= qty;
                if (remaining == 0) {
                    break;
                }
            }
        }
        return new MatchPlan(in, seq, fills, changes, remaining);
    }

    /**
     * 커밋된 계획을 반영한다. 계획을 만든 뒤 목록이 바뀌었으면 아무것도 바꾸지 않고 예외를 던진다.
     * 이 예외는 DB와 메모리가 어긋났다는 뜻이므로 호출자는 종목을 멈추고 DB에서 재구성해야 한다.
     */
    public void apply(MatchPlan plan) {
        for (CounterpartyChange change : plan.counterparties()) {
            RestingOrder current = byOrderId.get(change.before().orderId());
            if (!change.before().equals(current)) {
                throw new IllegalStateException(
                        "book changed since plan: expected " + change.before() + " but was " + current);
            }
        }
        if (seqs.contains(plan.seq()) || byOrderId.containsKey(plan.incoming().orderId())) {
            throw new IllegalStateException("incoming order already in book: " + plan.incoming().orderId());
        }

        for (CounterpartyChange change : plan.counterparties()) {
            remove(change.before());
            if (!change.filled()) {
                insert(change.before().withRemaining(change.remainingAfter()));
            }
        }
        plan.restingIncoming().ifPresent(this::insert);
    }

    /** 우선순위 순서의 주문 목록 사본. */
    public List<RestingOrder> orders(Side side) {
        List<RestingOrder> result = new ArrayList<>();
        side(side).values().forEach(level -> result.addAll(level.values()));
        return result;
    }

    private static boolean crosses(IncomingOrder in, long restingPrice) {
        return in.side() == Side.BUY ? restingPrice <= in.limitPrice() : restingPrice >= in.limitPrice();
    }

    private static Fill fill(IncomingOrder in, RestingOrder resting, int qty) {
        if (in.side() == Side.BUY) {
            return new Fill(in.orderId(), resting.orderId(), in.accountId(), resting.accountId(),
                    in.limitPrice(), resting.limitPrice(), qty);
        }
        return new Fill(resting.orderId(), in.orderId(), resting.accountId(), in.accountId(),
                resting.limitPrice(), resting.limitPrice(), qty);
    }

    private NavigableMap<Long, NavigableMap<Long, RestingOrder>> side(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private void insert(RestingOrder order) {
        side(order.side()).computeIfAbsent(order.limitPrice(), p -> new TreeMap<>()).put(order.seq(), order);
        byOrderId.put(order.orderId(), order);
        seqs.add(order.seq());
    }

    private void remove(RestingOrder order) {
        NavigableMap<Long, RestingOrder> level = side(order.side()).get(order.limitPrice());
        level.remove(order.seq());
        if (level.isEmpty()) {
            side(order.side()).remove(order.limitPrice());
        }
        byOrderId.remove(order.orderId());
        seqs.remove(order.seq());
    }
}
