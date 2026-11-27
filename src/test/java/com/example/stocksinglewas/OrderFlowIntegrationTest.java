package com.example.stocksinglewas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.example.stocksinglewas.order.dto.PlaceOrderRequest;
import com.example.stocksinglewas.order.entity.Side;
import com.example.stocksinglewas.matching.engine.SymbolPreparer;
import com.example.stocksinglewas.order.service.OrderIntakeService;
import com.example.stocksinglewas.support.MySqlIntegrationTest;
import com.example.stocksinglewas.support.TestData;

/**
 * HTTP 접수 → 커밋 후 전달 → 종목 워커 → T2 커밋까지 실제로 이어지는지 확인한다.
 * 워커는 별도 스레드이므로 DB 상태가 바뀔 때까지 짧게 폴링한다.
 */
class OrderFlowIntegrationTest extends MySqlIntegrationTest {

    static final long BUYER = 1;
    static final long SELLER = 2;

    @Autowired
    WebApplicationContext context;

    @Autowired
    SymbolPreparer preparer;

    @Autowired
    OrderIntakeService intake;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;
    TestData db;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        db = new TestData(jdbc);
        db.clear();
        db.symbol("AAA");
        db.account(BUYER, 100_000);
        db.account(SELLER, 0);
        db.holding(SELLER, "AAA", 10);
    }

    @AfterEach
    void stopEngines() {
        preparer.stopAll();
    }

    long placeViaHttp(long account, String clientOrderId, String side, long price, int qty) throws Exception {
        MvcResult result = mvc.perform(post("/api/accounts/{id}/orders", account)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientOrderId":"%s","symbolCode":"AAA","side":"%s","limitPrice":%d,"quantity":%d}
                                """.formatted(clientOrderId, side, price, qty)))
                .andExpect(status().isAccepted())
                .andReturn();
        return Long.parseLong(result.getResponse().getContentAsString().replaceAll("\\D+", ""));
    }

    void awaitStatus(long orderId, String expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (!expected.equals(db.status(orderId))) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("order " + orderId + " stayed " + db.status(orderId) + ", expected " + expected);
            }
            Thread.sleep(20);
        }
    }

    @Test
    void HTTP로_낸_매수와_매도가_종목_워커에서_체결된다() throws Exception {
        preparer.prepare("AAA");

        long sell = placeViaHttp(SELLER, "s1", "SELL", 1_000, 5);
        awaitStatus(sell, "OPEN");
        long buy = placeViaHttp(BUYER, "b1", "BUY", 1_100, 5);
        awaitStatus(buy, "FILLED");

        assertThat(db.status(sell)).isEqualTo("FILLED");
        assertThat(db.cash(BUYER)).isEqualTo(95_000);
        assertThat(db.cash(SELLER)).isEqualTo(5_000);
        assertThat(db.holdingOf(BUYER, "AAA")).containsExactly(5, 0);
        assertThat(db.count("trades")).isEqualTo(1);
    }

    @Test
    void 체결_결과를_조회_API로_볼_수_있고_없는_계좌는_404다() throws Exception {
        preparer.prepare("AAA");
        long sell = placeViaHttp(SELLER, "s1", "SELL", 1_000, 3);
        awaitStatus(sell, "OPEN");

        mvc.perform(get("/api/symbols/AAA/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asks[0].price").value(1_000))
                .andExpect(jsonPath("$.asks[0].quantity").value(3));
        mvc.perform(get("/api/accounts/{id}/orders", SELLER))
                .andExpect(jsonPath("$[0].orderId").value(sell))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
        mvc.perform(get("/api/accounts/{id}/holdings", SELLER))
                .andExpect(jsonPath("$[0].sellableQuantity").value(7));
        mvc.perform(get("/api/accounts/{id}/balance", 999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void 거절된_주문은_오류_코드를_응답한다() throws Exception {
        mvc.perform(post("/api/accounts/{id}/orders", BUYER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientOrderId":"b1","symbolCode":"AAA","side":"BUY","limitPrice":1000,"quantity":101}
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_CASH"));
    }

    @Test
    void 잘못된_요청은_400을_응답한다() throws Exception {
        mvc.perform(post("/api/accounts/{id}/orders", BUYER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientOrderId":"","symbolCode":"AAA","side":"BUY","limitPrice":0,"quantity":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    /** 엔진이 없을 때 접수된 주문(RECEIVED)은 엔진을 준비하면 DB에서 읽어 처리된다. */
    @Test
    void 엔진_시작_전에_접수된_주문은_엔진이_뜨면_DB에서_읽어_처리한다() throws Exception {
        long buy = intake.receive(BUYER, new PlaceOrderRequest("b1", "AAA", Side.BUY, 1_000, 5));

        preparer.prepare("AAA");

        awaitStatus(buy, "OPEN");
    }

    /** 재시작해도 DB의 대기 주문을 호가창에 되살리고, 순번은 기존 최댓값 다음부터 쓴다. */
    @Test
    void 재시작해도_DB의_대기_주문이_복원되고_처리순번이_이어진다() throws Exception {
        long sell = intake.receive(SELLER, new PlaceOrderRequest("s1", "AAA", Side.SELL, 1_000, 5));
        db.markOpen(sell, 5);

        preparer.prepare("AAA");
        long buy = placeViaHttp(BUYER, "b1", "BUY", 1_000, 5);
        awaitStatus(buy, "FILLED");

        assertThat(db.status(sell)).isEqualTo("FILLED");
        assertThat(jdbc.queryForObject("SELECT seq FROM orders WHERE id = ?", Long.class, buy)).isEqualTo(6);
    }
}
