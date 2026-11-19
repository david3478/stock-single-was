package com.example.stocksinglewas.service;

import static com.example.stocksinglewas.entity.Side.BUY;
import static com.example.stocksinglewas.entity.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.stocksinglewas.dao.OrderDao;
import com.example.stocksinglewas.dto.PlaceOrderRequest;
import com.example.stocksinglewas.entity.Order;
import com.example.stocksinglewas.entity.Side;
import com.example.stocksinglewas.matching.IncomingOrder;
import com.example.stocksinglewas.matching.MatchPlan;
import com.example.stocksinglewas.matching.OrderBook;
import com.example.stocksinglewas.matching.RestingOrder;
import com.example.stocksinglewas.support.MySqlIntegrationTest;
import com.example.stocksinglewas.support.TestData;

/**
 * 체결확정Service.확정: 주문 갱신, 체결 저장, 양쪽 정산, 알림 저장이 한 트랜잭션(T2)이다.
 * 하나라도 어긋나면 전부 롤백되어야 하므로 실제 MySQL로 검증한다.
 */
class MatchConfirmServiceTest extends MySqlIntegrationTest {

    static final long BUYER = 1;
    static final long SELLER = 2;
    static final long SELLER2 = 3;

    @Autowired
    MatchConfirmService confirm;

    @Autowired
    OrderIntakeService intake;

    @Autowired
    OrderDao orders;

    @Autowired
    JdbcTemplate jdbc;

    TestData db;
    OrderBook book;

    @BeforeEach
    void seed() {
        db = new TestData(jdbc);
        db.clear();
        db.symbol("AAA");
        db.account(BUYER, 100_000);
        db.account(SELLER, 0);
        db.account(SELLER2, 0);
        db.holding(SELLER, "AAA", 10);
        db.holding(SELLER2, "AAA", 10);
        book = new OrderBook();
    }

    /** 접수(T1)로 주문을 만든다. 예약까지 실제 경로로 된다. */
    long place(long account, String clientOrderId, Side side, long price, int qty) {
        return intake.receive(account, new PlaceOrderRequest(clientOrderId, "AAA", side, price, qty));
    }

    /** 접수 후 이미 처리되어 호가창에 있는 대기 주문으로 만든다. */
    long rest(long account, String clientOrderId, Side side, long price, int qty, long seq) {
        long id = place(account, clientOrderId, side, price, qty);
        db.markOpen(id, seq);
        book.add(new RestingOrder(id, account, side, price, qty, seq));
        return id;
    }

    MatchPlan planFor(long orderId, long seq) {
        Order o = orders.findById(orderId).orElseThrow();
        return book.plan(new IncomingOrder(o.id(), o.accountId(), o.side(), o.limitPrice(), o.quantity()), seq);
    }

    List<Map<String, Object>> notifications() {
        return jdbc.queryForList("SELECT account_id, order_id, trade_id, type FROM notifications ORDER BY id");
    }

    // --- 성공 ---

    @Test
    void 전량_체결되면_양쪽_계좌를_정산하고_체결과_알림을_저장한다() {
        long sell = rest(SELLER, "s1", SELL, 1_000, 5, 1);
        long buy = place(BUYER, "b1", BUY, 1_100, 5);

        boolean applied = confirm.confirm("AAA", planFor(buy, 2));

        assertThat(applied).isTrue();
        assertThat(db.status(buy)).isEqualTo("FILLED");
        assertThat(db.status(sell)).isEqualTo("FILLED");
        assertThat(jdbc.queryForObject("SELECT seq FROM orders WHERE id = ?", Long.class, buy)).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT buy_order_id, sell_order_id, price, quantity FROM trades"))
                .containsEntry("buy_order_id", buy).containsEntry("sell_order_id", sell)
                .containsEntry("price", 1_000L).containsEntry("quantity", 5);
        // 매수: 1,100원에 예약, 1,000원에 체결 → 차액 500원 환불, 총현금은 체결대금 5,000원만 감소
        assertThat(db.cash(BUYER)).isEqualTo(95_000);
        assertThat(db.reservedCash(BUYER)).isZero();
        assertThat(db.holdingOf(BUYER, "AAA")).containsExactly(5, 0);
        // 매도: 대금 수령, 총보유와 예약 수량이 함께 감소
        assertThat(db.cash(SELLER)).isEqualTo(5_000);
        assertThat(db.holdingOf(SELLER, "AAA")).containsExactly(5, 0);
        assertThat(notifications()).extracting(n -> n.get("account_id"), n -> n.get("type"))
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(BUYER, "ORDER_FILLED"),
                        org.assertj.core.groups.Tuple.tuple(SELLER, "ORDER_FILLED"));
        assertThat(notifications()).allSatisfy(n -> assertThat(n.get("trade_id")).isNotNull());
    }

    @Test
    void 상대가_없으면_대기_등록하고_체결_없는_알림을_저장한다() {
        long buy = place(BUYER, "b1", BUY, 1_000, 5);

        confirm.confirm("AAA", planFor(buy, 1));

        assertThat(jdbc.queryForMap("SELECT status, seq, remaining FROM orders WHERE id = ?", buy))
                .containsEntry("status", "OPEN").containsEntry("seq", 1L).containsEntry("remaining", 5);
        assertThat(db.count("trades")).isZero();
        assertThat(db.reservedCash(BUYER)).isEqualTo(5_000);
        assertThat(notifications()).singleElement().satisfies(n -> {
            assertThat(n.get("type")).isEqualTo("ORDER_OPEN");
            assertThat(n.get("trade_id")).isNull();
        });
    }

    @Test
    void 여러_상대와_체결되면_마지막_상대가_부분_체결로_남는다() {
        long first = rest(SELLER, "s1", SELL, 1_000, 3, 1);
        long second = rest(SELLER2, "s2", SELL, 1_000, 4, 2);
        long buy = place(BUYER, "b1", BUY, 1_000, 5);

        confirm.confirm("AAA", planFor(buy, 3));

        assertThat(db.status(buy)).isEqualTo("FILLED");
        assertThat(db.status(first)).isEqualTo("FILLED");
        assertThat(jdbc.queryForMap("SELECT status, remaining, seq FROM orders WHERE id = ?", second))
                .containsEntry("status", "PARTIALLY_FILLED").containsEntry("remaining", 2).containsEntry("seq", 2L);
        assertThat(db.count("trades")).isEqualTo(2);
        assertThat(db.holdingOf(SELLER2, "AAA")).containsExactly(8, 2);
        assertThat(notifications()).filteredOn(n -> n.get("account_id").equals(BUYER))
                .extracting(n -> n.get("type")).containsExactly("ORDER_PARTIALLY_FILLED", "ORDER_FILLED");
    }

    @Test
    void 들어온_주문의_남은_수량은_부분_체결_상태로_대기한다() {
        rest(SELLER, "s1", SELL, 1_000, 3, 1);
        long buy = place(BUYER, "b1", BUY, 1_000, 5);

        confirm.confirm("AAA", planFor(buy, 2));

        assertThat(jdbc.queryForMap("SELECT status, remaining FROM orders WHERE id = ?", buy))
                .containsEntry("status", "PARTIALLY_FILLED").containsEntry("remaining", 2);
        assertThat(db.reservedCash(BUYER)).isEqualTo(2_000);
    }

    @Test
    void 대기_중인_매수에_매도하면_매수_가격으로_체결된다() {
        long buy = rest(BUYER, "b1", BUY, 1_200, 5, 1);
        long sell = place(SELLER, "s1", SELL, 1_000, 5);

        confirm.confirm("AAA", planFor(sell, 2));

        assertThat(db.status(buy)).isEqualTo("FILLED");
        assertThat(db.cash(SELLER)).isEqualTo(6_000);
        assertThat(db.cash(BUYER)).isEqualTo(94_000);
        assertThat(db.reservedCash(BUYER)).isZero();
    }

    // --- 실패: 아무것도 바뀌지 않는다 ---

    @Test
    void 이미_처리된_주문이면_아무것도_바꾸지_않고_false를_돌려준다() {
        rest(SELLER, "s1", SELL, 1_000, 5, 1);
        long buy = place(BUYER, "b1", BUY, 1_000, 5);
        MatchPlan plan = planFor(buy, 2);
        db.markOpen(buy, 99);

        boolean applied = confirm.confirm("AAA", plan);

        assertThat(applied).isFalse();
        assertThat(db.count("trades")).isZero();
        assertThat(db.count("notifications")).isZero();
        assertThat(db.cash(SELLER)).isZero();
    }

    @Test
    void 상대_주문이_계획과_다르면_전부_롤백한다() {
        long sell = rest(SELLER, "s1", SELL, 1_000, 5, 1);
        long buy = place(BUYER, "b1", BUY, 1_000, 5);
        MatchPlan plan = planFor(buy, 2);
        jdbc.update("UPDATE orders SET status = 'FILLED', remaining = 0 WHERE id = ?", sell);

        assertThatThrownBy(() -> confirm.confirm("AAA", plan)).isInstanceOf(IllegalStateException.class);

        assertThat(db.status(buy)).isEqualTo("RECEIVED");
        assertThat(db.count("trades")).isZero();
        assertThat(db.count("notifications")).isZero();
        assertThat(db.reservedCash(BUYER)).isEqualTo(5_000);
        assertThat(db.holdingOf(BUYER, "AAA")).containsExactly(0, 0);
    }
}
