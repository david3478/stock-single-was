// SSE 수집기: 지정한 계좌마다 SSE를 연결해 받은 알림을 기록한다(docs/measurement/plan-stage10.md 6절).
// Java 21 단일 파일, 표준 라이브러리만 쓴다: java SseCollector.java --base http://HOST --accounts 1-500 --out DIR [옵션]
//
// 기록(모두 같은 벽시계 epoch 밀리초, System.currentTimeMillis()):
//   events.csv  account,gen,event_id,type,order_id,trade_id,recv_ms
//   markers.csv account,gen,kind,value,ms   kind: request(값=보낸 Last-Event-ID, 재연결의 L), connected, replayed(값=R),
//               disconnect(값=끊을 때의 마지막 번호, 참고용), closed, error. 단절 구간 판정의 L은 request 값을 쓴다
//   stats.csv   ms,received_total,received_1s,queue_size,max_queue_wait_ms
// 받는 순간 시각을 먼저 찍고 파일 기록은 별도 스레드가 한다. stats의 queue_size·max_queue_wait_ms로 기록이 밀리는지 본다.
//
// 모드
//   기본: 모두 연결 → :connected를 모두 받으면 "READY"를 출력하고 OUT/ready 파일을 만든다 → --duration-ms 동안 받거나 OUT/stop 파일이 생기면 끝낸다.
//   QAS 5(--cycles N): READY 뒤 --start-delay-ms 기다리고, 회차마다 모든 연결을 동시에 끊고(직전 마지막 알림 번호 L 기록)
//     --down-ms 뒤 Last-Event-ID=L로 다시 연결, 모두 :replayed를 받을 때까지(최대 --replay-wait-ms) 기다린 뒤 --settle-ms 쉰다.
//     회차 전 호가창 매도 잔량 합이 --min-asks보다 적거나, 단절 구간 체결 알림 수신 누계(추정)가 --target-window-fills 이상이면 멈춘다.
//     실제 분모·누락은 측정 뒤 DB와 대조해 계산한다. 수집기의 누계는 멈출 시점을 정하는 추정값일 뿐이다.
//     회차가 끝나면 "CYCLES_DONE"을 출력하고, 기본 모드처럼 OUT/stop 파일(또는 --duration-ms)까지 계속 받는다.
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SseCollector {

    static final Pattern ORDER_ID = Pattern.compile("\"orderId\"\\s*:\\s*(\\d+)");
    static final Pattern TRADE_ID = Pattern.compile("\"tradeId\"\\s*:\\s*(\\d+)");
    static final Pattern ASKS = Pattern.compile("\"asks\"\\s*:\\s*\\[(.*?)\\]");
    static final Pattern QTY = Pattern.compile("\"quantity\"\\s*:\\s*(\\d+)");

    final Map<String, String> opt;
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    final Path out;
    final Recorder events;
    final Recorder markers;
    final AtomicLong received = new AtomicLong();
    final AtomicLong windowFills = new AtomicLong();
    final List<Conn> conns = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            opt.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        new SseCollector(opt).run();
    }

    SseCollector(Map<String, String> opt) throws IOException {
        this.opt = opt;
        this.out = Path.of(require("out"));
        Files.createDirectories(out);
        this.events = new Recorder(out.resolve("events.csv"), "account,gen,event_id,type,order_id,trade_id,recv_ms");
        this.markers = new Recorder(out.resolve("markers.csv"), "account,gen,kind,value,ms");
    }

    String require(String key) {
        String v = opt.get(key);
        if (v == null) throw new IllegalArgumentException("missing --" + key);
        return v;
    }

    long num(String key, long def) {
        return opt.containsKey(key) ? Long.parseLong(opt.get(key)) : def;
    }

    void run() throws Exception {
        for (long account : parseAccounts(require("accounts"))) {
            conns.add(new Conn(account));
        }
        Thread stats = Thread.ofPlatform().daemon().start(this::statsLoop);

        CountDownLatch ready = new CountDownLatch(conns.size());
        conns.forEach(c -> c.open(null, ready));
        if (!ready.await(num("connect-timeout-ms", 60_000), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            System.out.println("NOT_READY connected=" + (conns.size() - ready.getCount()) + "/" + conns.size());
        }
        Files.writeString(out.resolve("ready"), Long.toString(System.currentTimeMillis()));
        System.out.println("READY " + conns.size());

        long until = System.currentTimeMillis() + num("duration-ms", 600_000);
        long cycles = num("cycles", 0);
        if (cycles > 0) {
            runCycles(cycles);
            System.out.println("CYCLES_DONE");
        }
        // 회차가 끝나도 부하가 멈추고 밀린 알림을 다 받을 때까지 계속 받는다. 측정 절차가 OUT/stop 파일로 끝을 알린다
        while (System.currentTimeMillis() < until && !Files.exists(out.resolve("stop"))) {
            Thread.sleep(200);
        }
        // 관찰 종료 뒤 잠깐 더 받아서 늦게 온 알림을 기록한다
        Thread.sleep(num("drain-ms", 2_000));
        conns.forEach(Conn::close);
        stats.interrupt();
        events.close();
        markers.close();
        System.out.println("DONE received=" + received.get() + " windowFillsEstimate=" + windowFills.get());
    }

    void runCycles(long cycles) throws Exception {
        Thread.sleep(num("start-delay-ms", 15_000));
        long target = num("target-window-fills", 100_000);
        for (int cycle = 1; cycle <= cycles; cycle++) {
            long asks = remainingAsks();
            if (asks >= 0 && asks < num("min-asks", 0)) {
                System.out.println("STOP cycle=" + cycle + " reason=asks remaining=" + asks);
                return;
            }
            if (windowFills.get() >= target) {
                System.out.println("STOP cycle=" + cycle + " reason=target windowFillsEstimate=" + windowFills.get());
                return;
            }
            long downAt = System.currentTimeMillis();
            conns.forEach(Conn::disconnect);
            Thread.sleep(num("down-ms", 5_000));
            CountDownLatch replayed = new CountDownLatch(conns.size());
            conns.forEach(c -> c.reconnect(replayed));
            boolean all = replayed.await(num("replay-wait-ms", 30_000), java.util.concurrent.TimeUnit.MILLISECONDS);
            System.out.println("CYCLE " + cycle + " down_ms=" + downAt + " replayed_all=" + all
                    + " windowFillsEstimate=" + windowFills.get() + " asks=" + asks);
            Thread.sleep(num("settle-ms", 10_000));
        }
    }

    /** 호가창 매도 잔량 합. --symbols가 없으면 -1(확인 안 함). */
    long remainingAsks() {
        String symbols = opt.get("symbols");
        if (symbols == null) return -1;
        long total = 0;
        for (String symbol : symbols.split(",")) {
            try {
                String body = http.send(HttpRequest.newBuilder(URI.create(require("base") + "/api/symbols/" + symbol
                                + "/orderbook?depth=50")).timeout(Duration.ofSeconds(10)).build(),
                        HttpResponse.BodyHandlers.ofString()).body();
                Matcher a = ASKS.matcher(body);
                if (!a.find()) throw new IllegalStateException("no asks in " + body);
                Matcher m = QTY.matcher(a.group(1));
                while (m.find()) total += Long.parseLong(m.group(1));
            } catch (Exception e) {
                System.out.println("WARN orderbook " + symbol + ": " + e);
            }
        }
        return total;
    }

    void statsLoop() {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("stats.csv")))) {
            w.println("ms,received_total,received_1s,queue_size,max_queue_wait_ms");
            long last = 0;
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(1_000);
                long total = received.get();
                w.printf("%d,%d,%d,%d,%d%n", System.currentTimeMillis(), total, total - last,
                        events.queue.size(), events.takeMaxWait());
                w.flush();
                last = total;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.out.println("WARN stats: " + e);
        }
    }

    static List<Long> parseAccounts(String spec) {
        List<Long> ids = new ArrayList<>();
        for (String part : spec.split(",")) {
            String[] r = part.split("-");
            long from = Long.parseLong(r[0].trim());
            long to = r.length > 1 ? Long.parseLong(r[1].trim()) : from;
            for (long a = from; a <= to; a++) ids.add(a);
        }
        return ids;
    }

    /** 계좌 하나의 SSE 연결. 재연결할 때마다 gen이 1씩 는다. lastEventId는 이 연결의 읽기 스레드만 쓴다. */
    final class Conn {
        final long account;
        volatile int gen;
        volatile long lastEventId;
        volatile InputStream body;
        volatile boolean inWindow;   // 재연결 직후 :replayed를 받기 전까지 받은 알림은 단절 구간(L, R]에 속한다
        volatile long windowFrom;
        volatile boolean closing;   // 이 수집기가 스스로 닫는 중
        int signaledGen;            // 이 gen의 연결 완료(또는 :replayed)를 이미 센 gen. 재시도해도 한 번만 센다

        Conn(long account) {
            this.account = account;
        }

        void open(Long lastId, CountDownLatch signal) {
            closing = false;
            int g = ++gen;
            Thread.ofVirtual().start(() -> read(g, lastId, signal));
        }

        void disconnect() {
            markers.add(account + "," + gen + ",disconnect," + lastEventId + "," + System.currentTimeMillis());
            close();
        }

        void reconnect(CountDownLatch replayed) {
            windowFrom = lastEventId;
            inWindow = true;
            // --test-skip N: 검증 전용. 재연결 때 Last-Event-ID를 N만큼 크게 보내 일부러 누락을 만든다(분석이 누락을 잡는지 확인)
            open(lastEventId + num("test-skip", 0), replayed);
        }

        void close() {
            closing = true;
            InputStream b = body;
            if (b != null) {
                try {
                    b.close();
                } catch (IOException ignored) {
                    // 이미 닫힘
                }
            }
        }

        /**
         * 브라우저 EventSource처럼, 연결이 실패하거나 서버가 끊으면 --retry-ms(기본 3초, 서버 retry 값과 같음) 뒤
         * 그때까지 받은 마지막 번호로 다시 붙는다. 시도마다 request 표시를 남기고, 같은 gen 안에서 첫 request 값이 이 연결의 L이다.
         * 이 수집기가 스스로 닫은 경우(disconnect)에는 다시 붙지 않는다.
         */
        private void read(int g, Long lastId, CountDownLatch signal) {
            long maxAttempts = num("max-attempts", 10);
            Long sendId = lastId;
            for (int attempt = 1; attempt <= maxAttempts && !closing && gen == g; attempt++) {
                if (attempt > 1) {
                    try {
                        Thread.sleep(num("retry-ms", 3_000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (closing || gen != g) return;
                    sendId = lastEventId > 0 || lastId != null ? (Long) lastEventId : null;
                }
                readOnce(g, sendId, lastId != null, signal);
            }
            if (!closing && gen == g) {
                markers.add(account + "," + g + ",error,gave_up," + System.currentTimeMillis());
            }
        }

        private void readOnce(int g, Long sendId, boolean reconnecting, CountDownLatch signal) {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(require("base") + "/api/accounts/" + account
                    + "/notifications")).header("Accept", "text/event-stream");
            if (sendId != null) req.header("Last-Event-ID", Long.toString(sendId));
            markers.add(account + "," + g + ",request," + (sendId == null ? "" : sendId) + "," + System.currentTimeMillis());
            try {
                HttpResponse<InputStream> res = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) {
                    markers.add(account + "," + g + ",error,http_" + res.statusCode() + "," + System.currentTimeMillis());
                    res.body().close();
                    return;
                }
                body = res.body();
                BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
                String line;
                String id = null, type = null, data = null;
                long firstLineMs = 0;
                while ((line = lines.readLine()) != null) {
                    long now = System.currentTimeMillis();
                    if (line.isEmpty()) {
                        if (id != null) {
                            onEvent(g, Long.parseLong(id), type, data, firstLineMs);
                        }
                        id = type = data = null;
                        firstLineMs = 0;
                        continue;
                    }
                    if (firstLineMs == 0) firstLineMs = now;
                    if (line.startsWith(":")) {
                        onComment(g, line.substring(1).trim(), now, signal, reconnecting);
                        firstLineMs = 0;
                    } else if (line.startsWith("id:")) {
                        id = line.substring(3).trim();
                    } else if (line.startsWith("event:")) {
                        type = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        data = line.substring(5).trim();
                    }
                }
            } catch (IOException e) {
                // 의도한 단절(disconnect)이 아니면 원인을 남긴다. 조용히 삼키면 연결 실패가 보이지 않는다
                if (!closing) {
                    markers.add(account + "," + g + ",error," + e.getClass().getSimpleName() + ":"
                            + String.valueOf(e.getMessage()).replace(',', ';') + "," + System.currentTimeMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            markers.add(account + "," + g + ",closed,," + System.currentTimeMillis());
        }

        private void onComment(int g, String comment, long now, CountDownLatch signal, boolean reconnecting) {
            if (comment.equals("connected")) {
                markers.add(account + "," + g + ",connected,," + now);
                if (!reconnecting && signaledGen != g) {
                    signaledGen = g;
                    signal.countDown();
                }
            } else if (comment.startsWith("replayed")) {
                markers.add(account + "," + g + ",replayed," + comment.substring(8).trim() + "," + now);
                inWindow = false;
                if (reconnecting && signaledGen != g) {
                    signaledGen = g;
                    signal.countDown();
                }
            }
        }

        private void onEvent(int g, long eventId, String type, String data, long recvMs) {
            received.incrementAndGet();
            lastEventId = eventId;
            String orderId = group(ORDER_ID, data);
            String tradeId = group(TRADE_ID, data);
            if (inWindow && eventId > windowFrom && !tradeId.isEmpty()) {
                windowFills.incrementAndGet();
            }
            events.add(account + "," + g + "," + eventId + "," + type + "," + orderId + "," + tradeId + "," + recvMs);
        }
    }

    static String group(Pattern p, String text) {
        if (text == null) return "";
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** 줄을 큐에 넣고 별도 스레드가 파일에 쓴다. 큐에서 가장 오래 기다린 시간을 잰다. */
    static final class Recorder {
        final BlockingQueue<Object[]> queue = new LinkedBlockingQueue<>();
        final AtomicLong maxWait = new AtomicLong();
        final Thread writer;
        volatile boolean closing;

        Recorder(Path file, String header) throws IOException {
            PrintWriter w = new PrintWriter(Files.newBufferedWriter(file));
            w.println(header);
            writer = Thread.ofPlatform().start(() -> {
                try {
                    while (!closing || !queue.isEmpty()) {
                        Object[] item = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (item == null) continue;
                        maxWait.accumulateAndGet(System.currentTimeMillis() - (long) item[1], Math::max);
                        w.println((String) item[0]);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    w.close();
                }
            });
        }

        void add(String line) {
            queue.add(new Object[] {line, System.currentTimeMillis()});
        }

        long takeMaxWait() {
            return maxWait.getAndSet(0);
        }

        void close() throws InterruptedException {
            closing = true;
            writer.join();
        }
    }
}
