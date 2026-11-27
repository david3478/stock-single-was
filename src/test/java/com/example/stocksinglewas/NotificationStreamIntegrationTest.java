package com.example.stocksinglewas;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.stocksinglewas.matching.engine.SymbolPreparer;
import com.example.stocksinglewas.support.MySqlIntegrationTest;
import com.example.stocksinglewas.support.TestData;

/**
 * ADR 0003 검증 3(SSE 끝단). HTTP 접수 → 종목 워커 → T2 커밋 → 전송예약 → SSE 수신까지 실제 서버로 확인한다.
 *
 * <p>주문이 처리되는 동안 한 연결은 끊었다가 마지막으로 받은 번호(Last-Event-ID)로 다시 붙는다.
 * 재연결 직후의 놓친 알림 재전송과 새 체결 신호가 겹쳐도, 연결마다 받은 번호 목록은
 * DB의 그 계좌 알림과 같아야 한다. 누락과 중복은 따로 센다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationStreamIntegrationTest extends MySqlIntegrationTest {

    static final long WATCHED = 1;
    static final long COUNTERPARTY = 2;
    static final List<String> SYMBOLS = List.of("E1", "E2", "E3");
    static final int PAIRS_PER_SYMBOL = 30;
    static final Duration WAIT = Duration.ofSeconds(20);

    @Value("${local.server.port}")
    int port;

    @Autowired
    SymbolPreparer preparer;

    @Autowired
    JdbcTemplate jdbc;

    final HttpClient http = HttpClient.newHttpClient();
    final List<SseClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        preparer.stopAll();
        TestData db = new TestData(jdbc);
        db.clear();
        db.account(WATCHED, 100_000_000);
        db.account(COUNTERPARTY, 0);
        for (String symbol : SYMBOLS) {
            db.symbol(symbol);
            db.holding(COUNTERPARTY, symbol, PAIRS_PER_SYMBOL);
            preparer.prepare(symbol);
        }
    }

    @AfterEach
    void tearDown() {
        clients.forEach(SseClient::disconnect);
        preparer.stopAll();
    }

    @Test
    void 주문이_몰리는_중에_재연결해도_연결마다_받은_알림은_DB와_같다() throws Exception {
        SseClient steady = connect(null);
        SseClient reconnecting = connect(null);

        for (int i = 0; i < PAIRS_PER_SYMBOL; i++) {
            // 8회마다: 끊어 둔 동안 주문을 처리시켜 놓친 알림을 만든 뒤, 주문이 계속 들어오는 중에 재연결한다
            boolean reconnectNow = i % 8 == 7;
            if (reconnectNow) {
                reconnecting.disconnect();
            }
            for (String symbol : SYMBOLS) {
                // 매도가 먼저 대기하고 매수가 체결하거나, 매수가 먼저 대기하고 매도가 체결한다
                boolean buyFirst = i % 2 == 0;
                place(buyFirst ? WATCHED : COUNTERPARTY, symbol, buyFirst ? "BUY" : "SELL", symbol + "-a" + i);
                place(buyFirst ? COUNTERPARTY : WATCHED, symbol, buyFirst ? "SELL" : "BUY", symbol + "-b" + i);
            }
            if (reconnectNow) {
                awaitProcessed();
                reconnecting = connect(reconnecting);
            }
        }

        List<Long> stored = awaitAllDelivered(steady, reconnecting);
        assertSameAsStored("계속 연결된 탭", steady.received(), stored);
        assertSameAsStored("재연결한 탭", reconnecting.receivedAcrossReconnects(), stored);
        assertThat(reconnecting.connections()).as("실제로 재연결했다").isGreaterThan(1);
    }

    @Test
    void 없는_계좌로_구독하면_404를_응답한다() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/api/accounts/999/notifications"))
                        .header("Accept", "text/event-stream").build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    static void assertSameAsStored(String who, List<Long> received, List<Long> stored) {
        List<Long> missing = stored.stream().filter(id -> !received.contains(id)).toList();
        int duplicates = received.size() - new HashSet<>(received).size();
        assertThat(missing).as(who + ": 누락").isEmpty();
        assertThat(duplicates).as(who + ": 중복").isZero();
        assertThat(received).as(who + ": 번호 순서").isEqualTo(stored);
    }

    void awaitProcessed() throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE status = 'RECEIVED'", Integer.class) > 0) {
            assertThat(Instant.now()).as("주문 처리 대기").isBefore(deadline);
            Thread.sleep(20);
        }
    }

    /** 모든 주문이 처리되고 두 연결이 DB의 알림 수만큼 받을 때까지 기다린다. */
    List<Long> awaitAllDelivered(SseClient... watching) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            Integer unprocessed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM orders WHERE status = 'RECEIVED'", Integer.class);
            List<Long> stored = jdbc.queryForList(
                    "SELECT id FROM notifications WHERE account_id = ? ORDER BY id", Long.class, WATCHED);
            boolean delivered = true;
            for (SseClient c : watching) {
                delivered &= c.receivedAcrossReconnects().size() >= stored.size();
            }
            if (unprocessed == 0 && delivered) {
                return stored;
            }
            Thread.sleep(50);
        }
        return jdbc.queryForList(
                "SELECT id FROM notifications WHERE account_id = ? ORDER BY id", Long.class, WATCHED);
    }

    void place(long account, String symbol, String side, String clientOrderId) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/api/accounts/" + account + "/orders"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"clientOrderId":"%s","symbolCode":"%s","side":"%s","limitPrice":1000,"quantity":1}
                                """.formatted(clientOrderId, symbol, side)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
    }

    /** 새로 연결한다. previous가 있으면 그 연결이 마지막으로 받은 번호를 Last-Event-ID로 보낸다. */
    SseClient connect(SseClient previous) throws Exception {
        SseClient client = new SseClient(previous);
        client.open(http, uri("/api/accounts/" + WATCHED + "/notifications"),
                previous == null ? null : previous.lastEventId());
        clients.add(client);
        return client;
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /**
     * 브라우저 EventSource 역할. id 줄을 받은 순서대로 기록한다.
     * 재연결하면 이전 연결이 받은 목록을 이어서 들고 간다.
     */
    static final class SseClient {
        private final List<Long> received = Collections.synchronizedList(new ArrayList<>());
        private final List<Long> before;
        private final int connections;
        private final CountDownLatch opened = new CountDownLatch(1);
        private volatile InputStream body;
        private Thread reader;

        SseClient(SseClient previous) {
            this.before = previous == null ? List.of() : previous.receivedAcrossReconnects();
            this.connections = previous == null ? 1 : previous.connections + 1;
        }

        void open(HttpClient http, URI uri, Long lastEventId) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).header("Accept", "text/event-stream");
            if (lastEventId != null) {
                request.header("Last-Event-ID", Long.toString(lastEventId));
            }
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            body = response.body();
            reader = Thread.ofPlatform().name("sse-client").start(this::read);
            // 서버는 연결을 등록한 뒤 첫 주석을 보낸다. 이것을 받으면 등록이 끝난 것이다
            assertThat(opened.await(5, TimeUnit.SECONDS)).as("SSE 연결 열림").isTrue();
        }

        private void read() {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.startsWith(":connected")) {
                        opened.countDown();
                    } else if (line.startsWith("id:")) {
                        received.add(Long.parseLong(line.substring(3).trim()));
                    }
                }
            } catch (IOException e) {
                // disconnect()가 스트림을 닫으면 여기로 온다
            }
        }

        void disconnect() {
            try {
                if (body != null) {
                    body.close();
                }
                if (reader != null) {
                    reader.join(5_000);
                }
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }

        /** 재연결할 때 보낼 Last-Event-ID. 이 연결 또는 이전 연결들이 마지막으로 받은 번호. */
        long lastEventId() {
            List<Long> all = receivedAcrossReconnects();
            return all.isEmpty() ? 0 : all.getLast();
        }

        List<Long> received() {
            synchronized (received) {
                return List.copyOf(received);
            }
        }

        List<Long> receivedAcrossReconnects() {
            List<Long> all = new ArrayList<>(before);
            all.addAll(received());
            return all;
        }

        int connections() {
            return connections;
        }
    }
}
