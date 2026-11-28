package com.example.stocksinglewas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

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

import com.example.stocksinglewas.matching.engine.SymbolPreparer;
import com.example.stocksinglewas.support.MySqlIntegrationTest;
import com.example.stocksinglewas.support.TestData;

/**
 * 실행 중 종목 추가(ADR 0014, QAS 6). HTTP 등록 요청 → 준비중 등록 → 엔진 준비 → 활성화 → 주문 체결까지 실제 DB로 확인한다.
 * 신규 종목의 매도 물량은 종목 추가 기능과 별개의 시험 데이터로 넣는다. holdings가 symbols를 외래 키로 참조하므로 종목이 생긴 뒤에 넣는다.
 */
class SymbolRegistrationIntegrationTest extends MySqlIntegrationTest {

    static final long BUYER = 1;
    static final long SELLER = 2;

    @Autowired
    WebApplicationContext context;

    @Autowired
    SymbolPreparer preparer;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;
    TestData db;

    @BeforeEach
    void setUp() {
        preparer.stopAll();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        db = new TestData(jdbc);
        db.clear();
        db.symbol("AAA");
        db.account(BUYER, 10_000_000);
        db.account(SELLER, 0);
        db.holding(SELLER, "AAA", 10_000);
    }

    @AfterEach
    void stopEngines() {
        preparer.stopAll();
    }

    int addSymbol(String code, String name) throws Exception {
        return mvc.perform(post("/api/symbols").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\",\"name\":\"%s\"}".formatted(code, name)))
                .andReturn().getResponse().getStatus();
    }

    MvcResult placeOrder(long account, String symbol, String clientOrderId, String side) throws Exception {
        return mvc.perform(post("/api/accounts/{id}/orders", account).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientOrderId":"%s","symbolCode":"%s","side":"%s","limitPrice":1000,"quantity":1}
                                """.formatted(clientOrderId, symbol, side)))
                .andReturn();
    }

    long orderId(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        return Long.parseLong(result.getResponse().getContentAsString().replaceAll("\\D+", ""));
    }

    String symbolStatus(String code) {
        return jdbc.queryForObject("SELECT status FROM symbols WHERE code = ?", String.class, code);
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    // --- 성공 ---

    @Test
    void 추가_전에는_주문을_거절하고_추가하면_ACTIVE가_되어_체결된다() throws Exception {
        assertThat(placeOrder(BUYER, "NEW1", "b0", "BUY").getResponse().getStatus()).isEqualTo(422);

        assertThat(addSymbol("NEW1", "신규1")).isEqualTo(201);

        assertThat(symbolStatus("NEW1")).isEqualTo("ACTIVE");
        db.holding(SELLER, "NEW1", 10);   // 시험 데이터: 신규 종목의 매도 물량(종목 추가 기능과 별개)
        long sell = orderId(placeOrder(SELLER, "NEW1", "s1", "SELL"));
        long buy = orderId(placeOrder(BUYER, "NEW1", "b1", "BUY"));
        await("first fill on NEW1", () -> "FILLED".equals(db.status(buy)) && "FILLED".equals(db.status(sell)));
    }

    @Test
    void 추가한_종목은_종목_목록에_ACTIVE로_보인다() throws Exception {
        addSymbol("NEW1", "신규1");

        mvc.perform(get("/api/symbols"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'NEW1')].status").value("ACTIVE"));
    }

    @Test
    void 같은_요청을_다시_보내면_200이고_엔진을_다시_만들지_않는다() throws Exception {
        assertThat(addSymbol("NEW1", "신규1")).isEqualTo(201);
        long nextSeq = preparer.status("NEW1").orElseThrow().nextSeq();

        assertThat(addSymbol("NEW1", "신규1")).isEqualTo(200);

        assertThat(preparer.status("NEW1").orElseThrow().nextSeq()).isEqualTo(nextSeq);
    }

    @Test
    void 준비중으로_남은_종목은_재시작해도_열리지_않고_같은_요청을_다시_보내면_완료된다() throws Exception {
        jdbc.update("INSERT INTO symbols (code, name, status) VALUES ('NEW1', '신규1', 'PREPARING')");
        preparer.prepareActiveSymbols();   // 재시작의 기동 복구
        assertThat(preparer.status("NEW1")).isEmpty();
        assertThat(placeOrder(BUYER, "NEW1", "b0", "BUY").getResponse().getStatus()).isEqualTo(422);

        assertThat(addSymbol("NEW1", "신규1")).isEqualTo(201);

        assertThat(symbolStatus("NEW1")).isEqualTo("ACTIVE");
        assertThat(preparer.status("NEW1")).isPresent();
    }

    @Test
    void 같은_종목을_동시에_여러_번_추가해도_하나만_201이고_ACTIVE가_된다() throws Exception {
        // 같은 키 INSERT 경합에서 InnoDB가 일부를 데드락으로 끝내는 경우가 드물게 나와서, 라운드를 반복해 경합 기회를 늘린다
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int round = 1; round <= 5; round++) {
            String code = "NEW" + round;
            CyclicBarrier start = new CyclicBarrier(threads);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return addSymbol(code, "신규");
                }));
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : results) {
                codes.add(f.get(15, TimeUnit.SECONDS));
            }

            assertThat(codes).as(code).filteredOn(c -> c == 201).hasSize(1);
            assertThat(codes).as(code).filteredOn(c -> c == 200).hasSize(threads - 1);
            assertThat(symbolStatus(code)).isEqualTo("ACTIVE");
            assertThat(preparer.symbols()).containsOnlyOnce(code);
        }
        pool.shutdown();
    }

    @Test
    void 기존_종목에_주문이_들어오는_중에_종목을_추가해도_기존_주문은_오류_없이_모두_처리된다() throws Exception {
        preparer.prepare("AAA");
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        ExecutorService load = Executors.newSingleThreadExecutor();
        Future<?> orders = load.submit(() -> {
            for (int i = 0; running.get(); i++) {
                try {
                    MvcResult r = placeOrder(i % 2 == 0 ? BUYER : SELLER, "AAA", "load-" + i, i % 2 == 0 ? "BUY" : "SELL");
                    if (r.getResponse().getStatus() == 202) accepted.incrementAndGet();
                    else unexpected.incrementAndGet();
                } catch (Exception e) {
                    unexpected.incrementAndGet();
                }
            }
            return null;
        });
        await("some load before adding", () -> accepted.get() > 20);

        for (int n = 1; n <= 3; n++) {
            assertThat(addSymbol("NEW" + n, "신규" + n)).isEqualTo(201);
        }
        int beforeStop = accepted.get();
        await("load continues after adding", () -> accepted.get() > beforeStop + 20);
        running.set(false);
        orders.get(15, TimeUnit.SECONDS);
        load.shutdown();

        assertThat(unexpected.get()).as("기존 종목 주문의 예상하지 못한 오류").isZero();
        await("all AAA orders processed", () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE symbol_code = 'AAA' AND status = 'RECEIVED'", Integer.class) == 0);
        assertThat(db.count("orders")).isEqualTo(accepted.get());
    }

    // --- 실패 ---

    @Test
    void 같은_코드를_다른_이름으로_추가하면_409이고_기존_종목은_그대로다() throws Exception {
        addSymbol("NEW1", "신규1");

        mvc.perform(post("/api/symbols").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"NEW1\",\"name\":\"다른이름\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYMBOL_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT name FROM symbols WHERE code = 'NEW1'", String.class)).isEqualTo("신규1");
    }

    @Test
    void 종목_코드_형식이_틀리면_400이고_등록하지_않는다() throws Exception {
        assertThat(addSymbol("bad code!", "이름")).isEqualTo(400);
        assertThat(addSymbol("", "이름")).isEqualTo(400);
        assertThat(addSymbol("NEW1", "")).isEqualTo(400);

        assertThat(db.count("symbols")).isEqualTo(1);
    }
}
