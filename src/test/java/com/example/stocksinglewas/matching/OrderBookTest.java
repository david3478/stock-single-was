package com.example.stocksinglewas.matching;

import static com.example.stocksinglewas.entity.Side.BUY;
import static com.example.stocksinglewas.entity.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import com.example.stocksinglewas.entity.Side;

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
    void 상대가_없으면_주문_전체가_대기_등록된다() {
        MatchPlan plan = book.plan(incoming(1, 10, BUY, 1000, 5), 1);

        assertThat(plan.fills()).isEmpty();
        assertThat(plan.incomingRemaining()).isEqualTo(5);

        book.apply(plan);

        assertThat(book.orders(BUY)).containsExactly(resting(1, 10, BUY, 1000, 5, 1));
    }

    @Test
    void 가격이_맞지_않으면_체결되지_않는다() {
        book.add(resting(1, 10, SELL, 1010, 5, 1));

        MatchPlan plan = book.plan(incoming(2, 20, BUY, 1000, 5), 2);

        assertThat(plan.fills()).isEmpty();
        assertThat(plan.incomingRemaining()).isEqualTo(5);
    }

    @Test
    void 매수는_가장_낮은_매도호가부터_체결된다() {
        book.add(resting(1, 10, SELL, 1020, 5, 1));
        book.add(resting(2, 11, SELL, 1000, 5, 2));

        MatchPlan plan = book.plan(incoming(3, 20, BUY, 1050, 5), 3);

        assertThat(plan.fills()).extracting(Fill::sellOrderId).containsExactly(2L);
    }

    @Test
    void 매도는_가장_높은_매수호가부터_체결된다() {
        book.add(resting(1, 10, BUY, 1000, 5, 1));
        book.add(resting(2, 11, BUY, 1020, 5, 2));

        MatchPlan plan = book.plan(incoming(3, 20, SELL, 990, 5), 3);

        assertThat(plan.fills()).extracting(Fill::buyOrderId).containsExactly(2L);
    }

    @Test
    void 같은_가격이면_처리순번이_빠른_주문부터_체결된다() {
        book.add(resting(7, 10, SELL, 1000, 5, 9));
        book.add(resting(8, 11, SELL, 1000, 5, 4));

        MatchPlan plan = book.plan(incoming(9, 20, BUY, 1000, 5), 10);

        assertThat(plan.fills()).extracting(Fill::sellOrderId).containsExactly(8L);
    }

    @Test
    void 체결가는_먼저_대기한_주문의_가격이고_매수_지정가를_함께_기록한다() {
        book.add(resting(1, 10, SELL, 1000, 5, 1));

        MatchPlan plan = book.plan(incoming(2, 20, BUY, 1100, 5), 2);

        assertThat(plan.fills()).containsExactly(new Fill(2, 1, 20, 10, 1100, 1000, 5));
    }

    @Test
    void 주문_하나가_여러_상대와_나눠_체결된다() {
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
    void 체결_가능한_상대를_모두_쓰면_남은_수량이_대기_등록된다() {
        book.add(resting(1, 10, SELL, 1000, 30, 1));
        book.add(resting(2, 11, SELL, 1100, 30, 2));

        MatchPlan plan = book.plan(incoming(3, 20, BUY, 1000, 50), 3);
        book.apply(plan);

        assertThat(plan.incomingRemaining()).isEqualTo(20);
        assertThat(book.orders(BUY)).containsExactly(resting(3, 20, BUY, 1000, 20, 3));
        assertThat(book.orders(SELL)).containsExactly(resting(2, 11, SELL, 1100, 30, 2));
    }

    @Test
    void 체결계획을_만들어도_호가창은_바뀌지_않는다() {
        book.add(resting(1, 10, SELL, 1000, 30, 1));
        book.add(resting(2, 11, SELL, 1000, 70, 2));
        List<RestingOrder> before = book.orders(SELL);

        book.plan(incoming(3, 20, BUY, 1000, 100), 3);
        book.plan(incoming(4, 21, BUY, 1000, 100), 4);

        assertThat(book.orders(SELL)).isEqualTo(before);
        assertThat(book.orders(BUY)).isEmpty();
    }

    @Test
    void 체결계획의_관련_계좌는_중복_없이_오름차순이다() {
        book.add(resting(1, 30, SELL, 1000, 10, 1));
        book.add(resting(2, 10, SELL, 1000, 10, 2));
        book.add(resting(3, 30, SELL, 1000, 10, 3));

        MatchPlan plan = book.plan(incoming(4, 20, BUY, 1000, 30), 4);

        assertThat(plan.accountIds()).containsExactly(10L, 20L, 30L);
    }

    @Test
    void 체결이_없어도_주문한_계좌는_관련_계좌에_들어간다() {
        MatchPlan plan = book.plan(incoming(1, 20, BUY, 1000, 5), 1);

        assertThat(plan.accountIds()).containsExactly(20L);
    }

    // --- apply: 커밋 후 반영 ---

    @Test
    void 부분_체결된_상대_주문은_원래_우선순위를_유지한다() {
        book.add(resting(1, 10, SELL, 1000, 50, 1));
        book.add(resting(2, 11, SELL, 1000, 50, 2));

        book.apply(book.plan(incoming(3, 20, BUY, 1000, 20), 3));

        assertThat(book.orders(SELL)).containsExactly(
                resting(1, 10, SELL, 1000, 30, 1),
                resting(2, 11, SELL, 1000, 50, 2));
    }

    @Test
    void 전량_체결된_상대_주문은_호가창에서_빠진다() {
        book.add(resting(1, 10, SELL, 1000, 30, 1));
        book.add(resting(2, 11, SELL, 1000, 70, 2));

        book.apply(book.plan(incoming(3, 20, BUY, 1000, 100), 3));

        assertThat(book.orders(SELL)).isEmpty();
        assertThat(book.orders(BUY)).isEmpty();
    }

    @Test
    void 호가창이_바뀐_뒤의_오래된_계획은_반영을_거부하고_호가창을_그대로_둔다() {
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
    void 복원한_대기_주문은_가격_다음_처리순번_순으로_정렬된다() {
        book.add(resting(5, 10, BUY, 1000, 5, 8));
        book.add(resting(6, 11, BUY, 1010, 5, 9));
        book.add(resting(7, 12, BUY, 1000, 5, 3));

        assertThat(book.orders(BUY)).extracting(RestingOrder::orderId).containsExactly(6L, 7L, 5L);
    }

    @Test
    void 같은_주문번호는_두_번_등록할_수_없다() {
        book.add(resting(1, 10, BUY, 1000, 5, 1));

        assertThatThrownBy(() -> book.add(resting(1, 10, BUY, 1000, 5, 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 같은_처리순번은_두_번_등록할_수_없다() {
        book.add(resting(1, 10, BUY, 1000, 5, 1));

        assertThatThrownBy(() -> book.add(resting(2, 10, SELL, 1100, 5, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- 입력 검증 ---

    @Test
    void 가격이나_수량이_0_이하면_거절한다() {
        assertThatThrownBy(() -> incoming(1, 10, BUY, 0, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> incoming(1, 10, BUY, 1000, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resting(1, 10, BUY, 1000, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resting(1, 10, BUY, 1000, 5, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
