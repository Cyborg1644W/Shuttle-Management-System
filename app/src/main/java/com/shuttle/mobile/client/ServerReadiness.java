package com.shuttle.mobile.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Wait for a sleeping host using safe health GETs before sending an application request. */
public final class ServerReadiness {
    private static final long STARTUP_TIMEOUT_MS = 90000;
    private static final long CACHE_MS = 30000;
    private static final long RETRY_DELAY_MS = 1500;
    private static final int REQUEST_TIMEOUT_MS = 8000;
    private static final Map<String, State> states = new ConcurrentHashMap<>();

    private static final class State {
        long readyAtNanos;
        FutureTask<Void> pending;
    }

    private ServerReadiness() { }

    /** Runs on the caller's worker thread. Concurrent callers share one probe per server. */
    public static void ensureReady(String baseUrl) throws IOException {
        checkInterrupted();
        State state = states.computeIfAbsent(baseUrl, ignored -> new State());
        FutureTask<Void> pending;
        boolean owner = false;
        synchronized (state) {
            if (state.readyAtNanos != 0
                    && System.nanoTime() - state.readyAtNanos < TimeUnit.MILLISECONDS.toNanos(CACHE_MS)) {
                return;
            }
            if (state.pending == null) {
                state.pending = new FutureTask<>(() -> {
                    probe(baseUrl, STARTUP_TIMEOUT_MS, RETRY_DELAY_MS, REQUEST_TIMEOUT_MS);
                    synchronized (state) { state.readyAtNanos = System.nanoTime(); }
                    return null;
                });
                owner = true;
            }
            pending = state.pending;
        }
        try {
            if (owner) pending.run();
            pending.get(STARTUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw interrupted(e);
        } catch (TimeoutException e) {
            // A waiting caller must not cancel another caller's shared readiness probe.
            throw startupTimeout(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IOException("Could not check the server connection.", cause);
        } finally {
            if (owner) {
                synchronized (state) {
                    if (state.pending == pending) state.pending = null;
                }
            }
        }
    }

    // Package-private time limits let standard Java tests exercise the total deadline quickly.
    static void probe(String baseUrl, long timeoutMillis, long retryDelayMillis,
                      int requestTimeoutMillis) throws IOException {
        if (timeoutMillis <= 0 || retryDelayMillis < 0 || requestTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Invalid readiness time limits");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        IOException lastFailure = null;
        while (true) {
            checkInterrupted();
            if (System.nanoTime() >= deadline) throw startupTimeout(lastFailure);
            HttpURLConnection connection = null;
            try {
                String address = baseUrl.endsWith("/") ? baseUrl + "health" : baseUrl + "/health";
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "text/csv");
                connection.setConnectTimeout(remainingTimeout(deadline, requestTimeoutMillis));
                connection.setReadTimeout(remainingTimeout(deadline, requestTimeoutMillis));
                connection.connect();
                connection.setReadTimeout(remainingTimeout(deadline, requestTimeoutMillis));
                int status = connection.getResponseCode();
                if (status == 200) {
                    String text = readHealth(connection, deadline, requestTimeoutMillis);
                    if (!"status\nok".equals(text.replace("\r\n", "\n").trim())) {
                        throw new IOException("This address did not return a valid FleetFlow health check. Check the server address.");
                    }
                    checkInterrupted();
                    if (System.nanoTime() >= deadline) throw startupTimeout(lastFailure);
                    return;
                }
                if (status != 502 && status != 503 && status != 504) {
                    throw new IOException("The server health check returned HTTP " + status + ". Check the server address.");
                }
                lastFailure = new IOException("Server health check returned HTTP " + status);
            } catch (SocketTimeoutException e) {
                // Only the health GET is retried; no account, invite, shift or location is repeated.
                lastFailure = e;
            } finally {
                if (connection != null) connection.disconnect();
            }
            checkInterrupted();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw startupTimeout(lastFailure);
            long delay = Math.min(TimeUnit.MILLISECONDS.toNanos(retryDelayMillis), remaining);
            try {
                TimeUnit.NANOSECONDS.sleep(delay);
            } catch (InterruptedException e) {
                throw interrupted(e);
            }
        }
    }

    private static String readHealth(HttpURLConnection connection, long deadline,
                                      int requestTimeoutMillis) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (InputStream stream = connection.getInputStream()) {
            byte[] buffer = new byte[256];
            while (true) {
                checkInterrupted();
                connection.setReadTimeout(remainingTimeout(deadline, requestTimeoutMillis));
                int count = stream.read(buffer);
                if (count < 0) break;
                if (data.size() + count > 1024) {
                    throw new IOException("This address did not return a valid FleetFlow health check. Check the server address.");
                }
                data.write(buffer, 0, count);
            }
        }
        return new String(data.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int remainingTimeout(long deadline, int requestTimeoutMillis) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw startupTimeout(null);
        return (int) Math.max(1, Math.min(requestTimeoutMillis, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw interrupted(null);
    }

    private static InterruptedIOException interrupted(Throwable cause) {
        Thread.currentThread().interrupt();
        InterruptedIOException failure = new InterruptedIOException("The server connection check was interrupted.");
        if (cause != null) failure.initCause(cause);
        return failure;
    }

    private static IOException startupTimeout(Throwable cause) {
        return new IOException("The server is still starting. Please try again in a moment.", cause);
    }
}
