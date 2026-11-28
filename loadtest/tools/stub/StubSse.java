// 보정용 가짜 SSE 서버. 실제 앱과 같은 형식으로 연결 신호와 가짜 체결 알림을 보낸다(plan-stage10.md 7절).
// Java 21 단일 파일, 표준 라이브러리만: java StubSse.java --port 8091 --rate 4000 --out DIR
//
// GET /api/accounts/{id}/notifications 에 연결하면 "retry:3000", ":connected", ":replayed <Last-Event-ID 또는 0>"을 보내고,
// 그 뒤 모든 연결에 돌아가며 --rate(초당 전체 알림 수)로 ORDER_FILLED 알림을 보낸다. 알림 번호는 전역 일련번호다.
// 1초마다 실제로 보낸 알림 수와 연결 수를 OUT/stub-stats.csv에 남긴다. 목표 속도를 못 냈으면 수집기가 아니라 이 서버가 원인이다.
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class StubSse {

    record Sub(long account, OutputStream out, CountDownLatch done) {
    }

    final List<Sub> subs = new CopyOnWriteArrayList<>();
    final AtomicLong eventIds = new AtomicLong();
    final AtomicLong sent = new AtomicLong();

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) opt.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        int port = Integer.parseInt(opt.getOrDefault("port", "8091"));
        double rate = Double.parseDouble(opt.getOrDefault("rate", "0"));
        Path out = Path.of(opt.getOrDefault("out", "."));
        Files.createDirectories(out);
        new StubSse().run(port, rate, out);
    }

    void run(int port, double rate, Path out) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 4096);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/accounts/", this::subscribe);
        server.start();
        System.out.println("STUB_SSE port=" + port + " rate=" + rate);
        Thread.ofPlatform().daemon().start(() -> stats(out));
        Thread.ofPlatform().daemon().start(() -> heartbeat());
        if (rate > 0) emit(rate);
        Thread.currentThread().join();
    }

    void subscribe(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!path.endsWith("/notifications")) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        long account = Long.parseLong(path.split("/")[3]);
        String last = ex.getRequestHeaders().getFirst("Last-Event-ID");
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        Sub sub = new Sub(account, os, new CountDownLatch(1));
        synchronized (os) {
            os.write(("retry:3000\n\n:connected\n\n:replayed " + (last == null ? "0" : last) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        subs.add(sub);
        try {
            sub.done().await();   // 쓰기가 실패하면(클라이언트가 끊으면) 끝난다
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ex.close();
    }

    /** 초당 rate개를 연결들에 돌아가며 보낸다. 1ms 단위로 밀린 만큼 몰아서 보낸다. */
    void emit(double rate) {
        long start = System.nanoTime();
        long emitted = 0;
        int next = 0;
        while (true) {
            long due = (long) ((System.nanoTime() - start) / 1e9 * rate);
            while (emitted < due) {
                List<Sub> snapshot = subs;
                if (snapshot.isEmpty()) {
                    emitted = due;   // 연결이 없을 때 밀린 몫은 버린다
                    break;
                }
                Sub s = snapshot.get(next++ % snapshot.size());
                long id = eventIds.incrementAndGet();
                String event = "id:" + id + "\nevent:ORDER_FILLED\ndata:{\"orderId\":" + id + ",\"status\":\"FILLED\","
                        + "\"remaining\":0,\"tradeId\":" + id + ",\"price\":1000,\"quantity\":1}\n\n";
                if (write(s, event)) sent.incrementAndGet();
                emitted++;
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    boolean write(Sub s, String text) {
        try {
            synchronized (s.out()) {
                s.out().write(text.getBytes(StandardCharsets.UTF_8));
                s.out().flush();
            }
            return true;
        } catch (IOException e) {
            subs.remove(s);
            s.done().countDown();
            return false;
        }
    }

    void heartbeat() {
        while (true) {
            try {
                Thread.sleep(15_000);
            } catch (InterruptedException e) {
                return;
            }
            subs.forEach(s -> write(s, ":ping\n\n"));
        }
    }

    void stats(Path out) {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("stub-stats.csv")))) {
            w.println("ms,connections,sent_total,sent_1s");
            long last = 0;
            while (true) {
                Thread.sleep(1_000);
                long total = sent.get();
                w.printf("%d,%d,%d,%d%n", System.currentTimeMillis(), subs.size(), total, total - last);
                w.flush();
                last = total;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.out.println("WARN stats: " + e);
        }
    }
}
