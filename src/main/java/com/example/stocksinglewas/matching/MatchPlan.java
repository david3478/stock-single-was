package com.example.stocksinglewas.matching;

import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 주문 하나를 처리한 결과의 불변 계획(EA: 체결계획).
 * 호가창을 바꾸지 않고 만들며, T2 커밋이 성공한 뒤에만 {@link OrderBook#apply}로 반영한다.
 */
public record MatchPlan(
        IncomingOrder incoming,
        long seq,
        List<Fill> fills,
        List<CounterpartyChange> counterparties,
        int incomingRemaining) {

    public MatchPlan {
        fills = List.copyOf(fills);
        counterparties = List.copyOf(counterparties);
    }

    /** 입력 주문이 잔량을 남기면 대기주문목록에 등록될 모습. */
    public Optional<RestingOrder> restingIncoming() {
        if (incomingRemaining == 0) {
            return Optional.empty();
        }
        return Optional.of(new RestingOrder(
                incoming.orderId(), incoming.accountId(), incoming.side(),
                incoming.limitPrice(), incomingRemaining, seq));
    }

    /** T2에서 잠글 계좌. 잠금 순서를 고정하기 위해 오름차순이며 중복이 없다(ADR 0003). */
    public List<Long> accountIds() {
        TreeSet<Long> ids = new TreeSet<>();
        ids.add(incoming.accountId());
        counterparties.forEach(c -> ids.add(c.before().accountId()));
        return List.copyOf(ids);
    }
}
