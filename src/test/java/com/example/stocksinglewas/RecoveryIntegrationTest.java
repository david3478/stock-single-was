package com.example.stocksinglewas;

import static com.example.stocksinglewas.order.entity.Side.BUY;
import static com.example.stocksinglewas.order.entity.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.example.stocksinglewas.matching.engine.IncomingOrder;
import com.example.stocksinglewas.matching.engine.MatchPlan;
import com.example.stocksinglewas.matching.engine.OrderBook;
import com.example.stocksinglewas.matching.engine.OrderDispatcher;
import com.example.stocksinglewas.matching.engine.RestingOrder;
import com.example.stocksinglewas.matching.engine.SymbolPreparer;
import com.example.stocksinglewas.matching.service.MatchConfirmService;
import com.example.stocksinglewas.matching.service.MatchingMonitor;
import com.example.stocksinglewas.order.dao.OrderDao;
import com.example.stocksinglewas.order.dto.PlaceOrderRequest;
import com.example.stocksinglewas.order.entity.Order;
import com.example.stocksinglewas.order.entity.Side;
import com.example.stocksinglewas.order.service.OrderIntakeService;
import com.example.stocksinglewas.support.MySqlIntegrationTest;
import com.example.stocksinglewas.support.TestData;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 장애 테스트(ADR 0002, 0008, 0013, QAS 4). 서버가 죽으면 메모리(호가창, 대기열, 처리순번)는 사라지고
 * 커밋되지 않은 트랜잭션은 MySQL이 롤백한다. 그래서 "종료 후 재시작"을 엔진을 버리고 DB에서 다시 만드는 것으로 재현한다.
 * JVM 종료 자체(종료 훅 등)는 이 테스트의 대상이 아니다. 실제 강제 종료는 QAS 4 측정 절차로 확인한다.
 *
 * <p>대기 주문 복원이 여러 묶음으로 나뉘도록 묶음 크기를 2로 둔다.
 */
@TestPropertySource(properties = "trading.recovery.restore-batch-size=2")
class RecoveryIntegrationTest extends MySqlIntegrationTest {

    static final long BUYER = 1;
    static final long SELLER = 2;
    static final long SELLER2 = 3;
    static final long SELLER3 = 4;

    @Autowired
    SymbolPreparer preparer;

    @Autowired
    OrderIntakeService intake;

    @Autowired
    OrderDispatcher dispatcher;

    @Autowired
    MatchConfirmService confirm;

    @Autowired
    MatchingMonitor monitor;

    @Autowired
    OrderDao orders;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    WebApplicationContext context;

    TestData db;

    @BeforeEach
    void setUp() {
        preparer.stopAll();
        db = new TestData(jdbc);
        db.clear();
        db.symbol("AAA");
        db.account(BUYER, 1_000_000);
        for (long seller : new long[] {SELLER, SELLER2, SELLER3}) {
            db.account(seller, 0);
            db.holding(seller, "AAA", 10);
        }
    }

    @AfterEach
    void stopEngines() {
        preparer.stopAll();
    }

    long place(long account, String clientOrderId, Side side, long price, int qty) {
        return intake.receive(account, new PlaceOrderRequest(clientOrderId, "AAA", side, price, qty));
    }

    /** 서버 강제 종료: 메모리의 엔진을 모두 버린다. DB는 살아 있다(QAS 4). */
    void crash() {
        preparer.stopAll();
    }

    /** 재시작: 기동 복구와 같은 경로로 DB에서 엔진을 다시 만든다. */
    void restart() {
        preparer.prepare("AAA");
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    // --- T1 커밋 후 전달 전 종료 ---

    @Test
    void 접수_커밋_후_대기열에_넣기_전에_죽어도_재시작하면_한_번만_체결된다() throws Exception {
        long sell = place(SELLER, "s1", SELL, 1_000, 5);   // 엔진이 없어 대기열에 못 들어간다
        long buy = place(BUYER, "b1", BUY, 1_000, 5);
        assertThat(db.status(sell)).isEqualTo("RECEIVED");

        restart();

        await("both filled", () -> "FILLED".equals(db.status(buy)) && "FILLED".equals(db.status(sell)));
        assertThat(db.count("trades")).isEqualTo(1);
        assertThat(db.cash(SELLER)).isEqualTo(5_000);
    }

    @Test
    void 엔진이_쉬는데_전달이_빠진_주문이_있으면_감시가_두_번째에_경고하고_재시작하면_처리된다() throws Exception {
        restart();
        await("engine idle", () -> preparer.status("AAA").map(s -> !s.overflowed()).orElse(false));
        dispatcher.unregister("AAA");   // 전달 경로를 빠뜨린 버그를 흉내 낸다
        long sell = place(SELLER, "s1", SELL, 1_000, 5);
        double before = meters.counter("matching.stall.detected", "symbol", "AAA").count();

        monitor.check();
        monitor.check();

        assertThat(meters.counter("matching.stall.detected", "symbol", "AAA").count()).isGreaterThan(before);
        assertThat(db.status(sell)).as("감시는 경고만 하고 복구하지 않는다(ADR 0013)").isEqualTo("RECEIVED");
        crash();
        restart();
        await("sell processed", () -> "OPEN".equals(db.status(sell)));
    }

    // --- T2 커밋 전후 종료 ---

    @Test
    void 체결_확정_커밋_전에_죽으면_아무것도_남지_않고_재시작하면_다시_처리한다() throws Exception {
        long sell = place(SELLER, "s1", SELL, 1_000, 5);
        restart();
        await("sell open", () -> "OPEN".equals(db.status(sell)));
        crash();
        long buy = place(BUYER, "b1", BUY, 1_000, 5);
        MatchPlan plan = planAgainstDb(buy, sell);

        // T2를 끝까지 실행한 뒤 커밋 직전에 죽는다: 바깥 트랜잭션이 롤백되면 T2 전체가 사라진다
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            confirm.confirm("AAA", plan);
            throw new IllegalStateException("server killed before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(db.status(buy)).isEqualTo("RECEIVED");
        assertThat(db.count("trades")).isZero();

        restart();

        await("both filled", () -> "FILLED".equals(db.status(buy)) && "FILLED".equals(db.status(sell)));
        assertThat(db.count("trades")).isEqualTo(1);
    }

    @Test
    void 체결_확정_커밋_후_호가창_반영_전에_죽어도_재시작한_호가창은_DB와_같고_다시_체결하지_않는다() throws Exception {
        long sell = place(SELLER, "s1", SELL, 1_000, 5);
        restart();
        await("sell open", () -> "OPEN".equals(db.status(sell)));
        crash();
        long buy = place(BUYER, "b1", BUY, 1_000, 5);

        // T2는 커밋됐지만 메모리 호가창에는 반영하지 못하고 죽었다
        assertThat(confirm.confirm("AAA", planAgainstDb(buy, sell))).isTrue();
        restart();

        await("restarted engine idle", () -> preparer.status("AAA").map(s -> !s.overflowed()).orElse(false));
        assertThat(preparer.status("AAA")).hasValueSatisfying(s ->
                assertThat(s.restoredOrders()).as("이미 체결된 매도는 호가창에 없다").isZero());
        dispatcher.dispatch(buy, "AAA");   // 같은 주문이 다시 전달되어도 건너뛴다
        long buy2 = place(BUYER, "b2", BUY, 1_000, 5);
        await("second buy rests", () -> "OPEN".equals(db.status(buy2)));
        assertThat(db.count("trades")).isEqualTo(1);
        assertThat(db.cash(SELLER)).isEqualTo(5_000);
    }

    // --- 재시작 후 순서와 순번 ---

    @Test
    void 재시작하면_대기_주문이_원래_가격_시간_순서로_돌아오고_처리순번이_이어진다() throws Exception {
        long s1 = place(SELLER, "s1", SELL, 1_000, 1);
        long s2 = place(SELLER2, "s2", SELL, 1_000, 1);
        long s3 = place(SELLER3, "s3", SELL, 1_000, 1);
        long cheaper = place(SELLER3, "s4", SELL, 990, 1);
        restart();
        await("all open", () -> "OPEN".equals(db.status(cheaper)));
        long maxSeq = jdbc.queryForObject("SELECT MAX(seq) FROM orders WHERE symbol_code = 'AAA'", Long.class);
        crash();

        restart();   // 대기 주문 4건을 2건씩 묶어 읽는다
        assertThat(preparer.status("AAA")).hasValueSatisfying(s -> {
            assertThat(s.restoredOrders()).isEqualTo(4);
            assertThat(s.nextSeq()).isEqualTo(maxSeq + 1);
        });
        long buy = place(BUYER, "b1", BUY, 1_000, 3);

        await("buy filled", () -> "FILLED".equals(db.status(buy)));
        assertThat(db.status(cheaper)).as("가격 우선").isEqualTo("FILLED");
        assertThat(db.status(s1)).as("같은 가격이면 먼저 처리된 순서").isEqualTo("FILLED");
        assertThat(db.status(s2)).isEqualTo("FILLED");
        assertThat(db.status(s3)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT seq FROM orders WHERE id = ?", Long.class, buy)).isEqualTo(maxSeq + 1);
    }

    @Test
    void 기동_복구가_끝나면_readiness에_엔진_준비가_UP으로_보인다() throws Exception {
        MockMvcBuilders.webAppContextSetup(context).build()
                .perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        assertThat(preparer.health().getStatus().getCode()).isEqualTo("UP");
    }

    /** DB에 있는 대기 매도 하나를 상대로 입력 주문의 체결 계획을 만든다(죽기 전 엔진이 만들었을 계획). */
    MatchPlan planAgainstDb(long incomingId, long restingId) {
        Order r = orders.findById(restingId).orElseThrow();
        Order in = orders.findById(incomingId).orElseThrow();
        OrderBook book = new OrderBook();
        book.add(new RestingOrder(r.id(), r.accountId(), r.side(), r.limitPrice(), r.remaining(), r.seq()));
        long nextSeq = orders.maxSeq("AAA") + 1;
        return book.plan(new IncomingOrder(in.id(), in.accountId(), in.side(), in.limitPrice(), in.quantity()), nextSeq);
    }
}
