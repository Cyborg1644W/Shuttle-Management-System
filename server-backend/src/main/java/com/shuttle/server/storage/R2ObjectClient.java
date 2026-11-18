package com.shuttle.server.storage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Minimal S3-compatible, conditional single-object client for Cloudflare R2. */
public final class R2ObjectClient implements ObjectStore {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withLocale(Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withLocale(Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SCOPE_SUFFIX = "/auto/s3/aws4_request";
    private static final HexFormat HEX = HexFormat.of();

    private final URI objectUri;
    private final String canonicalHost;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final HttpClient http;
    private final Clock clock;

    /** Endpoint must be the HTTPS origin of the account's R2 S3 API. */
    public R2ObjectClient(URI endpoint, String bucket, String key,
                          String accessKeyId, String secretAccessKey) {
        this(endpoint, bucket, key, accessKeyId, secretAccessKey, Clock.systemUTC(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER).build(), false);
    }

    /** Dependency injection for deterministic tests; insecure HTTP is allowed only on explicit loopback origins. */
    public R2ObjectClient(URI endpoint, String bucket, String key,
                          String accessKeyId, String secretAccessKey, Clock clock,
                          HttpClient http, boolean allowInsecureLocalhost) {
        Objects.requireNonNull(endpoint, "endpoint");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.http = Objects.requireNonNull(http, "http");
        if (http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("R2 requests must not follow redirects");
        }
        String host = endpoint.getHost();
        String scheme = endpoint.getScheme();
        boolean loopback = host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]"));
        boolean secure = "https".equalsIgnoreCase(scheme);
        if ((!secure && !(allowInsecureLocalhost && loopback && "http".equalsIgnoreCase(scheme)))
                || host == null || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null || endpoint.getPort() == 0
                || (endpoint.getRawPath() != null && !endpoint.getRawPath().isEmpty()
                    && !endpoint.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("R2 endpoint must be an HTTPS origin without credentials, path, query or fragment");
        }
        if (bucket == null || bucket.isEmpty() || bucket.contains("/") || bucket.equals(".") || bucket.equals("..")) {
            throw new IllegalArgumentException("R2 bucket name is required and cannot contain a slash");
        }
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("R2 object key is required");
        }
        this.accessKeyId = requireCredential(accessKeyId);
        this.secretAccessKey = requireCredential(secretAccessKey);
        // S3 signs the exact escaped object path; never normalize its segments or form-encode spaces as '+'.
        String rawPath = "/" + uriEncode(bucket, false) + "/" + uriEncode(key, true);
        String authority = endpoint.getRawAuthority().toLowerCase(Locale.ROOT);
        this.objectUri = URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + authority + rawPath);
        this.canonicalHost = authority;
    }

    @Override
    public Versioned get() throws IOException {
        HttpResponse<byte[]> response = send("GET", new byte[0], null);
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw statusError("GET", response.statusCode());
        }
        return new Versioned(response.body(), requireEtag(response));
    }

    @Override
    public String put(byte[] data, String expectedEtag) throws IOException {
        byte[] payload = Objects.requireNonNull(data, "data").clone();
        if (expectedEtag != null) {
            validateEtag(expectedEtag);
        }
        HttpResponse<byte[]> response = send("PUT", payload, expectedEtag);
        if (response.statusCode() == 412) {
            throw new ConflictException("R2 object changed; reload before retrying the write");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw statusError("PUT", response.statusCode());
        }
        // A successful PUT with a missing ETag is ambiguous. Do not issue another write or claim a version.
        return requireEtag(response);
    }

    private HttpResponse<byte[]> send(String method, byte[] payload, String expectedEtag) throws IOException {
        Instant now = clock.instant();
        String timestamp = AMZ_DATE.format(now);
        String date = DATE.format(now);
        String payloadHash = HEX.formatHex(sha256(payload));
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", canonicalHost);
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", timestamp);
        if (method.equals("PUT")) {
            headers.put("content-type", "application/octet-stream");
            headers.put(expectedEtag == null ? "if-none-match" : "if-match", expectedEtag == null ? "*" : expectedEtag);
        }
        String signedHeaders = String.join(";", headers.keySet());
        StringBuilder canonicalHeaders = new StringBuilder();
        for (Map.Entry<String, String> h : headers.entrySet()) {
            canonicalHeaders.append(h.getKey()).append(':').append(h.getValue().trim().replaceAll("\\s+", " ")).append('\n');
        }
        String canonicalRequest = method + "\n" + objectUri.getRawPath() + "\n\n" + canonicalHeaders
                + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = date + SCOPE_SUFFIX;
        String stringToSign = ALGORITHM + "\n" + timestamp + "\n" + scope + "\n"
                + HEX.formatHex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        byte[] signingKey = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), date);
        signingKey = hmac(signingKey, "auto");
        signingKey = hmac(signingKey, "s3");
        signingKey = hmac(signingKey, "aws4_request");
        String signature = HEX.formatHex(hmac(signingKey, stringToSign));
        HttpRequest.Builder request = HttpRequest.newBuilder(objectUri).timeout(REQUEST_TIMEOUT);
        // Host is supplied by HttpClient from the URI and cannot be set through the restricted-header API.
        headers.forEach((name, value) -> {
            if (!name.equals("host")) request.header(name, value);
        });
        request.header("Authorization", ALGORITHM + " Credential=" + accessKeyId + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        request.method(method, method.equals("PUT") ? HttpRequest.BodyPublishers.ofByteArray(payload)
                : HttpRequest.BodyPublishers.noBody());
        // Bound the entire response, including a stalled body after successful response headers.
        // HttpRequest.timeout alone does not provide a complete body-read deadline.
        CompletableFuture<HttpResponse<byte[]>> response = http.sendAsync(request.build(), info -> {
            BoundedBodySubscriber subscriber = new BoundedBodySubscriber();
            try {
                if (info.headers().firstValueAsLong("Content-Length").orElse(-1) > MAX_RESPONSE_BYTES) {
                    subscriber.reject(new IOException("R2 response exceeds the 32 MiB limit"));
                }
            } catch (NumberFormatException e) {
                subscriber.reject(new IOException("R2 response has an invalid Content-Length", e));
            }
            return subscriber;
        });
        try {
            // Deliberately no retry loop: a transport error after PUT may have committed remotely.
            return response.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("R2 request interrupted", e);
        } catch (TimeoutException e) {
            response.cancel(true);
            throw new HttpTimeoutException("R2 request timed out before the complete response was received");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException failure) {
                throw failure;
            }
            throw new IOException("R2 request failed", cause);
        }
    }

    /** Requests one bounded batch at a time and aborts before accumulating an oversized response. */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private int received;

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription next) {
            if (subscription != null || body.isDone()) {
                next.cancel();
                return;
            }
            subscription = next;
            next.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                subscription.cancel();
                return;
            }
            long incoming = 0;
            for (ByteBuffer buffer : buffers) {
                incoming += buffer.remaining();
            }
            if (incoming > MAX_RESPONSE_BYTES - received) {
                reject(new IOException("R2 response exceeds the 32 MiB limit"));
                return;
            }
            byte[] chunk = new byte[8192];
            for (ByteBuffer buffer : buffers) {
                while (buffer.hasRemaining()) {
                    int length = Math.min(buffer.remaining(), chunk.length);
                    buffer.get(chunk, 0, length);
                    output.write(chunk, 0, length);
                    received += length;
                }
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            reject(error);
        }

        @Override
        public void onComplete() {
            if (!body.isDone()) {
                byte[] bytes = output.toByteArray();
                output = null;
                body.complete(bytes);
            }
        }

        private void reject(Throwable error) {
            output = null;
            body.completeExceptionally(error);
            if (subscription != null) {
                subscription.cancel();
            }
        }
    }

    private static String requireCredential(String value) {
        if (value == null || value.isBlank() || value.chars().anyMatch(c -> c <= 32 || c >= 127 || c == ',')) {
            throw new IllegalArgumentException("R2 credentials are required and must not contain whitespace or control characters");
        }
        return value;
    }

    private static String requireEtag(HttpResponse<?> response) throws IOException {
        String etag = response.headers().firstValue("ETag").orElse("").trim();
        try {
            validateEtag(etag);
        } catch (IllegalArgumentException e) {
            throw new IOException("R2 response did not contain a usable ETag", e);
        }
        return etag;
    }

    private static void validateEtag(String etag) {
        // Only one strong entity tag is meaningful for optimistic concurrency. Wildcards and lists
        // would silently turn an exact-version update into a less restrictive write.
        if (etag.length() < 2 || etag.charAt(0) != '"' || etag.charAt(etag.length() - 1) != '"') {
            throw new IllegalArgumentException("Expected one quoted strong ETag");
        }
        for (int i = 1; i < etag.length() - 1; i++) {
            char c = etag.charAt(i);
            if (c == '"' || c <= 32 || c == 127 || c > 255) {
                throw new IllegalArgumentException("Invalid ETag characters");
            }
        }
    }

    private static IOException statusError(String method, int status) {
        // Error bodies and headers may echo credential material. Return only operation and status.
        return new IOException("R2 " + method + " failed with HTTP " + status);
    }

    private static byte[] sha256(byte[] data) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static byte[] hmac(byte[] key, String value) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IOException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static String uriEncode(String value, boolean preserveSlash) {
        StringBuilder out = new StringBuilder();
        for (byte octet : value.getBytes(StandardCharsets.UTF_8)) {
            int c = octet & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '-' || c == '_' || c == '.' || c == '~' || preserveSlash && c == '/') {
                out.append((char) c);
            } else {
                out.append('%').append("0123456789ABCDEF".charAt(c >>> 4)).append("0123456789ABCDEF".charAt(c & 15));
            }
        }
        return out.toString();
    }
}
