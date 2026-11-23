package com.shuttle.mobile.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone JVM regression tests; no Android runtime or external service required. */
public final class ServerReadinessTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        readyServer();
        gatewayErrorsRetry();
        requestTimeoutRetries();
        clientErrorDoesNotRetry();
        wrongHealthBodyDoesNotRetry();
        overallDeadline();
        slowHealthBodyHonorsDeadline();
        concurrentRequestsShareReadiness();
        endpointCacheIsSeparate();
        interruptedProbePreservesInterrupt();
        System.out.println(passed + " server readiness tests passed");
    }

    private static void readyServer() throws Exception {
        try (TestServer server = new TestServer(exchange -> reply(exchange, 200, "status\nok\n"))) {
            ServerReadiness.probe(server.baseUrl(), 500, 5, 100);
            pass("valid CSV health response");
        }
    }

    private static void gatewayErrorsRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        int[] statuses = {502, 503, 504, 200};
        try (TestServer server = new TestServer(exchange -> {
            int n = requests.getAndIncrement();
            int status = statuses[Math.min(n, statuses.length - 1)];
            reply(exchange, status, status == 200 ? "status\nok\n" : "Starting");
        })) {
            ServerReadiness.probe(server.baseUrl(), 2000, 5, 200);
            check(requests.get() == 4, "Expected one retry for each cold-start gateway response");
            pass("502/503/504 retried before readiness");
        }
    }

    private static void requestTimeoutRetries() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            if (requests.incrementAndGet() == 1) {
                try { Thread.sleep(150); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            reply(exchange, 200, "status\nok\n");
        })) {
            ServerReadiness.probe(server.baseUrl(), 1000, 5, 40);
            check(requests.get() >= 2, "A timed-out health GET must be retried");
            pass("health request timeout retried safely");
        }
    }

    private static void clientErrorDoesNotRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            requests.incrementAndGet();
            reply(exchange, 404, "not found");
        })) {
            expectFailure(() -> ServerReadiness.probe(server.baseUrl(), 1000, 5, 100));
            check(requests.get() == 1, "404 should fail immediately");
            pass("permanent HTTP error does not retry");
        }
    }

    private static void wrongHealthBodyDoesNotRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            requests.incrementAndGet();
            reply(exchange, 200, "<html>Starting service</html>");
        })) {
            expectFailure(() -> ServerReadiness.probe(server.baseUrl(), 1000, 5, 100));
            check(requests.get() == 1, "An unrelated 200 response must not establish readiness");
            pass("unexpected health response rejected");
        }
    }

    private static void overallDeadline() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            requests.incrementAndGet();
            reply(exchange, 503, "Starting");
        })) {
            long start = System.nanoTime();
            expectFailure(() -> ServerReadiness.probe(server.baseUrl(), 130, 10, 50));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            check(elapsedMs < 750, "Probe exceeded its overall timeout: " + elapsedMs + "ms");
            check(requests.get() > 1, "The readiness deadline test must exercise retries");
            pass("overall wait bounded");
        }
    }

    private static void slowHealthBodyHonorsDeadline() throws Exception {
        AtomicInteger bytesSent = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/csv");
            byte[] body = "status\nok\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try {
                for (byte value : body) {
                    exchange.getResponseBody().write(value);
                    exchange.getResponseBody().flush();
                    bytesSent.incrementAndGet();
                    try { Thread.sleep(40); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            } finally { exchange.close(); }
        })) {
            long start = System.nanoTime();
            expectFailure(() -> ServerReadiness.probe(server.baseUrl(), 130, 5, 80));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            check(elapsedMs < 500, "Trickling response exceeded the overall deadline: " + elapsedMs + "ms");
            check(bytesSent.get() > 1, "The test must exercise several body reads");
            pass("trickling health body cannot keep a probe alive past its deadline");
        }
    }

    private static void concurrentRequestsShareReadiness() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            requests.incrementAndGet();
            try { Thread.sleep(100); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            reply(exchange, 200, "status\nok\n");
        })) {
            ExecutorService callers = Executors.newFixedThreadPool(8);
            try {
                CountDownLatch ready = new CountDownLatch(8);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> results = new ArrayList<>();
                for (int i = 0; i < 8; i++) results.add(callers.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        ServerReadiness.ensureReady(server.baseUrl());
                    } catch (Exception e) { throw new RuntimeException(e); }
                }));
                check(ready.await(2, TimeUnit.SECONDS), "Caller threads did not start");
                start.countDown();
                for (Future<?> result : results) result.get(3, TimeUnit.SECONDS);
                ServerReadiness.ensureReady(server.baseUrl());
                check(requests.get() == 1, "Concurrent and cached calls should use one health request, got " + requests.get());
                pass("concurrent callers coalesce and successful readiness is cached");
            } finally { callers.shutdownNow(); }
        }
    }

    private static void endpointCacheIsSeparate() throws Exception {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        try (TestServer a = new TestServer(exchange -> { first.incrementAndGet(); reply(exchange, 200, "status\nok\n"); });
             TestServer b = new TestServer(exchange -> { second.incrementAndGet(); reply(exchange, 200, "status\nok\n"); })) {
            ServerReadiness.ensureReady(a.baseUrl());
            ServerReadiness.ensureReady(b.baseUrl());
            check(first.get() == 1 && second.get() == 1, "Changing servers must not reuse the old server's readiness");
            pass("readiness cached separately for each server");
        }
    }

    private static void interruptedProbePreservesInterrupt() throws Exception {
        CountDownLatch contacted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        try (TestServer server = new TestServer(exchange -> {
            contacted.countDown();
            reply(exchange, 503, "Starting");
        })) {
            Thread probe = new Thread(() -> {
                try { ServerReadiness.probe(server.baseUrl(), 3000, 1000, 100); }
                catch (Throwable e) { failure.set(e); interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            probe.start();
            check(contacted.await(2, TimeUnit.SECONDS), "Probe did not contact server");
            probe.interrupt();
            probe.join(1500);
            check(!probe.isAlive(), "Interrupted readiness probe did not stop");
            check(failure.get() instanceof InterruptedIOException, "Expected InterruptedIOException but got " + failure.get());
            check(interrupted.get(), "Interrupted status was cleared");
            pass("interrupt cancels waiting and preserves interrupted status");
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/csv");
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally { exchange.close(); }
    }

    private interface CheckedRunnable { void run() throws Exception; }
    private static void expectFailure(CheckedRunnable action) throws Exception {
        try { action.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("Expected an IOException");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void pass(String description) {
        passed++;
        System.out.println("PASS " + description);
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        TestServer(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/health", handler);
            server.setExecutor(executor);
            server.start();
        }
        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/api"; }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
