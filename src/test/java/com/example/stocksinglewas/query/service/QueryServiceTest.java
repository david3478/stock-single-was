package com.example.stocksinglewas.query.service;

import static com.example.stocksinglewas.order.entity.Side.BUY;
import static com.example.stocksinglewas.order.entity.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.stocksinglewas.global.exception.NotFoundException;
import com.example.stocksinglewas.order.dto.PlaceOrderRequest;
import com.example.stocksinglewas.order.entity.Side;
import com.example.stocksinglewas.order.service.OrderIntakeService;
import com.example.stocksinglewas.query.dto.BalanceResponse;
import com.example.stocksinglewas.query.dto.HoldingResponse;
import com.example.stocksinglewas.query.dto.OrderBookResponse;
import com.example.stocksinglewas.query.dto.OrderHistoryResponse;
import com.example.stocksinglewas.query.dto.PriceLevel;
import com.example.stocksinglewas.support.MySqlIntegrationTest;
import com.example.stocksinglewas.support.TestData;

/** 조회Service: 결과가 SQL(집계, 정렬, 페이지)에 달려 있으므로 실제 MySQL로 검증한다. */
class QueryServiceTest extends MySqlIntegrationTest {

    static final long ACCOUNT = 1;
    static final long OTHER = 2;

    @Autowired
    QueryService query;

    @Autowired
    OrderIntakeService intake;

    @Autowired
    JdbcTemplate jdbc;

    TestData db;

    @BeforeEach
    void seed() {
        db = new TestData(jdbc);
        db.clear();
        db.symbol("AAA");
        db.symbol("BBB");
        db.account(ACCOUNT, 100_000);
        db.account(OTHER, 100_000);
        db.holding(ACCOUNT, "AAA", 10);
        db.holding(OTHER, "AAA", 10);
    }

    long place(long account, String clientOrderId, Side side, long price, int qty) {
        return intake.receive(account, new PlaceOrderRequest(clientOrderId, "AAA", side, price, qty));
    }

    long open(long account, String clientOrderId, Side side, long price, int qty, long seq) {
        long id = place(account, clientOrderId, side, price, qty);
        db.markOpen(id, seq);
        return id;
    }

    // --- 잔고 ---

    @Test
    void 잔고는_사용_가능_현금과_예약_현금을_보여준다() {
        place(ACCOUNT, "b1", BUY, 1_000, 30);

        assertThat(query.balance(ACCOUNT)).isEqualTo(new BalanceResponse(70_000, 30_000));
    }

    @Test
    void 없는_계좌의_잔고는_찾을_수_없다() {
        assertThatThrownBy(() -> query.balance(999)).isInstanceOf(NotFoundException.class);
    }

    // --- 보유 ---

    @Test
    void 보유_종목은_매도_가능_수량을_함께_보여준다() {
        place(ACCOUNT, "s1", SELL, 1_000, 4);

        assertThat(query.holdings(ACCOUNT)).containsExactly(new HoldingResponse("AAA", 10, 4, 6));
    }

    @Test
    void 보유량이_0인_종목은_보유_목록에서_빠진다() {
        db.holding(ACCOUNT, "BBB", 0);

        assertThat(query.holdings(ACCOUNT)).extracting(HoldingResponse::symbolCode).containsExactly("AAA");
    }

    @Test
    void 없는_계좌의_보유는_찾을_수_없다() {
        assertThatThrownBy(() -> query.holdings(999)).isInstanceOf(NotFoundException.class);
    }

    // --- 주문 내역 ---

    @Test
    void 주문_내역은_최신순이고_상태를_보여준다() {
        long first = place(ACCOUNT, "b1", BUY, 1_000, 1);
        long second = open(ACCOUNT, "b2", BUY, 1_000, 1, 1);

        List<OrderHistoryResponse> orders = query.orders(ACCOUNT, null, null);

        assertThat(orders).extracting(OrderHistoryResponse::orderId).containsExactly(second, first);
        assertThat(orders).extracting(OrderHistoryResponse::status).containsExactly("OPEN", "RECEIVED");
    }

    @Test
    void 주문_내역은_마지막으로_받은_주문번호_이전부터_이어서_조회한다() {
        long a = place(ACCOUNT, "b1", BUY, 1_000, 1);
        long b = place(ACCOUNT, "b2", BUY, 1_000, 1);
        long c = place(ACCOUNT, "b3", BUY, 1_000, 1);

        assertThat(query.orders(ACCOUNT, null, 2)).extracting(OrderHistoryResponse::orderId).containsExactly(c, b);
        assertThat(query.orders(ACCOUNT, b, 2)).extracting(OrderHistoryResponse::orderId).containsExactly(a);
    }

    @Test
    void 주문_내역에는_다른_계좌의_주문이_없다() {
        place(OTHER, "b1", BUY, 1_000, 1);

        assertThat(query.orders(ACCOUNT, null, null)).isEmpty();
    }

    @Test
    void 조회_건수는_최대값을_넘지_않고_1보다_작으면_1건이다() {
        for (int i = 0; i < 3; i++) {
            place(ACCOUNT, "b" + i, BUY, 1_000, 1);
        }

        assertThat(query.orders(ACCOUNT, null, 0)).hasSize(1);
        assertThat(query.orders(ACCOUNT, null, 1_000_000)).hasSize(3);
    }

    @Test
    void 없는_계좌의_주문_내역은_찾을_수_없다() {
        assertThatThrownBy(() -> query.orders(999, null, null)).isInstanceOf(NotFoundException.class);
    }

    // --- 호가창 ---

    @Test
    void 호가창은_가격별로_잔량을_합치고_매수는_높은_가격_매도는_낮은_가격부터다() {
        open(ACCOUNT, "b1", BUY, 990, 2, 1);
        open(OTHER, "b2", BUY, 990, 3, 2);
        open(ACCOUNT, "b3", BUY, 980, 1, 3);
        open(ACCOUNT, "s1", SELL, 1_010, 4, 4);
        open(OTHER, "s2", SELL, 1_020, 5, 5);

        OrderBookResponse book = query.orderBook("AAA", null);

        assertThat(book.bids()).containsExactly(new PriceLevel(990, 5, 2), new PriceLevel(980, 1, 1));
        assertThat(book.asks()).containsExactly(new PriceLevel(1_010, 4, 1), new PriceLevel(1_020, 5, 1));
    }

    @Test
    void 호가창에는_대기_중인_주문의_잔량만_나온다() {
        place(ACCOUNT, "received", BUY, 990, 1);
        long partial = open(ACCOUNT, "partial", BUY, 990, 5, 1);
        jdbc.update("UPDATE orders SET status = 'PARTIALLY_FILLED', remaining = 2 WHERE id = ?", partial);
        long filled = open(OTHER, "filled", BUY, 990, 5, 2);
        jdbc.update("UPDATE orders SET status = 'FILLED', remaining = 0 WHERE id = ?", filled);

        assertThat(query.orderBook("AAA", null).bids()).containsExactly(new PriceLevel(990, 2, 1));
    }

    @Test
    void 호가창은_요청한_깊이만큼만_보여준다() {
        open(ACCOUNT, "b1", BUY, 990, 1, 1);
        open(ACCOUNT, "b2", BUY, 980, 1, 2);
        open(ACCOUNT, "b3", BUY, 970, 1, 3);

        assertThat(query.orderBook("AAA", 2).bids()).extracting(PriceLevel::price).containsExactly(990L, 980L);
    }

    @Test
    void 없는_종목의_호가창은_찾을_수_없다() {
        assertThatThrownBy(() -> query.orderBook("NONE", null)).isInstanceOf(NotFoundException.class);
    }

    // --- 종목 ---

    @Test
    void 종목_목록은_코드순이다() {
        assertThat(query.symbols()).extracting(s -> s.code()).containsExactly("AAA", "BBB");
    }
}
