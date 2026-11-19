package com.example.stocksinglewas.matching;

import static com.example.stocksinglewas.matching.Side.BUY;
import static com.example.stocksinglewas.matching.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import org.junit.jupiter.api.Test;

class OrderBookTest {

    final OrderBook book = new OrderBook();

    static RestingOrder resting(long orderId, long accountId, Side side, long price, int remaining, long seq) {
        return new RestingOrder(orderId, accountId, side, price, remaining, seq);
    }

    static IncomingOrder incoming(long orderId, long accountId, Side side, long price, int quantity) {
        return new IncomingOrder(orderId, accountId, side, price, quantity);
    }

    // --- plan: 체결 계획 생성 ---

    @Test
    void restsWholeOrderWhenNoCounterparty() {
        MatchPlan plan = book.plan(incoming(1, 10, BUY, 1000, 5), 1);

        assertThat(plan.fills()).isEmpty();
        assertThat(plan.incomingRemaining()).isEqualTo(5);

        book.apply(plan);

        assertThat(book.orders(BUY)).containsExactly(resting(1, 10, BUY, 1000, 5, 1));
    }

    @Test
    void doesNotMatchWhenPricesDoNotCross() {
        book.add(resting(1, 10, SELL, 1010, 5, 1));

        MatchPlan plan = book.plan(incoming(2, 20, BUY, 1000, 5), 2);

        assertThat(plan.fills()).isEmpty();
        assertThat(plan.incomingRemaining()).isEqualTo(5);
    }

    @Test
    void buyMatchesLowestAskFirst() {
        book.add(resting(1, 10, SELL, 1020, 5, 1));
        book.add(resting(2, 11, SELL, 1000, 5, 2));

        MatchPlan plan = book.plan(incoming(3, 20, BUY, 1050, 5), 3);

        assertThat(plan.fills()).extracting(Fill::sellOrderId).containsExactly(2L);
    }

    @Test
    void sellMatchesHighestBidFirst() {
        book.add(resting(1, 10, BUY, 1000, 5, 1));
        book.add(resting(2, 11, BUY, 1020, 5, 2));

        MatchPlan plan = book.plan(incoming(3, 20, SELL, 990, 5), 3);

        assertThat(plan.fills()).extracting(Fill::buyOrderId).containsExactly(2L);
    }

    @Test
    void samePriceMatchesLowerSeqFirst() {
        book.add(resting(7, 10, SELL, 1000, 5, 9));
        book.add(resting(8, 11, SELL, 1000, 5, 4));

        MatchPlan plan = book.plan(incoming(9, 20, BUY, 1000, 5), 10);

        assertThat(plan.fills()).extracting(Fill::sellOrderId).containsExactly(8L);
    }

    @Test
    void tradesAtRestingOrderPriceAndKeepsBuyerLimit() {
        book.add(resting(1, 10, SELL, 1000, 5, 1));

        MatchPlan plan = book.plan(incoming(2, 20, BUY, 1100, 5), 2);

        assertThat(plan.fills()).containsExactly(new Fill(2, 1, 20, 10, 1100, 1000, 5));
    }

    @Test
    void oneIncomingOrderFillsAgainstSeveralCounterparties() {
        book.add(resting(1, 10, SELL, 1000, 30, 1));
        book.add(resting(2, 11, SELL, 1000, 70, 2));
        book.add(resting(3, 12, SELL, 1000, 50, 3));

        MatchPlan plan = book.plan(incoming(4, 20, BUY, 1000, 100), 4);

        assertThat(plan.fills())
                .extracting(Fill::sellOrderId, Fill::quantity)
                .containsExactly(tuple(1L, 30), tuple(2L, 70));
        assertThat(plan.incomingRemaining()).isZero();
    }

    @Test
    void restsRemainderAfterConsumingAllCrossingOrders() {
        book.add(resting(1, 10, SELL, 1000, 30, 1));
        book.add(resting(2, 11, SELL, 1100, 30, 2));

        MatchPlan plan = book.plan(incoming(3, 20, BUY, 1000, 50), 3);
        book.apply(plan);

        assertThat(plan.incomingRemaining()).isEqualTo(20);
        assertThat(book.orders(BUY)).containsExactly(resting(3, 20, BUY, 1000, 20, 3));
        assertThat(book.orders(SELL)).containsExactly(resting(2, 11, SELL, 1100, 30, 2));
    }

    @Test
    void planDoesNotChangeBook() {
        book.add(resting(1, 10, SELL, 1000, 30, 1));
        book.add(resting(2, 11, SELL, 1000, 70, 2));
        List<RestingOrder> before = book.orders(SELL);

        book.plan(incoming(3, 20, BUY, 1000, 100), 3);
        book.plan(incoming(4, 21, BUY, 1000, 100), 4);

        assertThat(book.orders(SELL)).isEqualTo(before);
        assertThat(book.orders(BUY)).isEmpty();
    }

    @Test
    void planListsRelatedAccountsAscendingWithoutDuplicates() {
        book.add(resting(1, 30, SELL, 1000, 10, 1));
        book.add(resting(2, 10, SELL, 1000, 10, 2));
        book.add(resting(3, 30, SELL, 1000, 10, 3));

        MatchPlan plan = book.plan(incoming(4, 20, BUY, 1000, 30), 4);

        assertThat(plan.accountIds()).containsExactly(10L, 20L, 30L);
    }

    @Test
    void planForUnmatchedOrderStillListsItsAccount() {
        MatchPlan plan = book.plan(incoming(1, 20, BUY, 1000, 5), 1);

        assertThat(plan.accountIds()).containsExactly(20L);
    }

    // --- apply: 커밋 후 반영 ---

    @Test
    void partiallyFilledCounterpartyKeepsOriginalPriority() {
        book.add(resting(1, 10, SELL, 1000, 50, 1));
        book.add(resting(2, 11, SELL, 1000, 50, 2));

        book.apply(book.plan(incoming(3, 20, BUY, 1000, 20), 3));

        assertThat(book.orders(SELL)).containsExactly(
                resting(1, 10, SELL, 1000, 30, 1),
                resting(2, 11, SELL, 1000, 50, 2));
    }

    @Test
    void removesFullyFilledCounterparties() {
        book.add(resting(1, 10, SELL, 1000, 30, 1));
        book.add(resting(2, 11, SELL, 1000, 70, 2));

        book.apply(book.plan(incoming(3, 20, BUY, 1000, 100), 3));

        assertThat(book.orders(SELL)).isEmpty();
        assertThat(book.orders(BUY)).isEmpty();
    }

    @Test
    void rejectsStalePlanAndLeavesBookUnchanged() {
        book.add(resting(1, 10, SELL, 1000, 30, 1));
        MatchPlan first = book.plan(incoming(2, 20, BUY, 1000, 10), 2);
        MatchPlan stale = book.plan(incoming(3, 21, BUY, 1000, 10), 3);
        book.apply(first);
        List<RestingOrder> afterFirst = book.orders(SELL);

        assertThatThrownBy(() -> book.apply(stale)).isInstanceOf(IllegalStateException.class);
        assertThat(book.orders(SELL)).isEqualTo(afterFirst);
        assertThat(book.orders(BUY)).isEmpty();
    }

    // --- add: 복구 시 잔여 주문 등록 ---

    @Test
    void addRestoresOrdersByPriceThenSeq() {
        book.add(resting(5, 10, BUY, 1000, 5, 8));
        book.add(resting(6, 11, BUY, 1010, 5, 9));
        book.add(resting(7, 12, BUY, 1000, 5, 3));

        assertThat(book.orders(BUY)).extracting(RestingOrder::orderId).containsExactly(6L, 7L, 5L);
    }

    @Test
    void rejectsDuplicateOrderId() {
        book.add(resting(1, 10, BUY, 1000, 5, 1));

        assertThatThrownBy(() -> book.add(resting(1, 10, BUY, 1000, 5, 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateSeq() {
        book.add(resting(1, 10, BUY, 1000, 5, 1));

        assertThatThrownBy(() -> book.add(resting(2, 10, SELL, 1100, 5, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- 입력 검증 ---

    @Test
    void rejectsNonPositivePriceOrQuantity() {
        assertThatThrownBy(() -> incoming(1, 10, BUY, 0, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> incoming(1, 10, BUY, 1000, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resting(1, 10, BUY, 1000, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resting(1, 10, BUY, 1000, 5, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
