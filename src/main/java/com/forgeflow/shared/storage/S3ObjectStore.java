package com.forgeflow.shared.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An S3 client in one class: PUT, GET, DELETE object and create-bucket, with
 * path-style URLs ({@code endpoint/bucket/key}) so it works against MinIO,
 * R2 or S3 alike, signed with {@link SigV4}. java.net.http, no SDK.
 *
 * Errors are exceptions, never silent: a lost write would be a lost file.
 * The one tolerated "error" is a 404 on GET, which is Optional.empty().
 */
public class S3ObjectStore implements ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStore.class);
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final URI endpoint;
    private final String region;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public S3ObjectStore(String endpoint, String region, String bucket, String accessKey, String secretKey) {
        this.endpoint = URI.create(endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        this.region = region;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    /** Create the bucket if it isn't there. Safe to call on every start. */
    public void ensureBucket() {
        HttpResponse<byte[]> r = send("PUT", "/" + bucket, new byte[0], null);
        if (r.statusCode() == 200) {
            log.info("created bucket {} at {}", bucket, endpoint);
        } else if (r.statusCode() == 409) {
            log.debug("bucket {} already exists", bucket);       // BucketAlreadyOwnedByYou
        } else {
            throw new StorageException("create bucket " + bucket, r);
        }
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        HttpResponse<byte[]> r = send("PUT", objectPath(key), content, contentType);
        if (r.statusCode() != 200) {
            throw new StorageException("PUT " + key, r);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        HttpResponse<byte[]> r = send("GET", objectPath(key), null, null);
        if (r.statusCode() == 404) {
            return Optional.empty();
        }
        if (r.statusCode() != 200) {
            throw new StorageException("GET " + key, r);
        }
        return Optional.of(r.body());
    }

    @Override
    public void delete(String key) {
        HttpResponse<byte[]> r = send("DELETE", objectPath(key), null, null);
        if (r.statusCode() != 204 && r.statusCode() != 200 && r.statusCode() != 404) {
            throw new StorageException("DELETE " + key, r);
        }
    }

    @Override
    public String describe() {
        return "s3://" + bucket + " @ " + endpoint;
    }

    private String objectPath(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.contains("..")) {
            throw new IllegalArgumentException("bad object key: " + key);
        }
        return "/" + bucket + "/" + key;
    }

    private HttpResponse<byte[]> send(String method, String rawPath, byte[] body, String contentType) {
        String path = SigV4.encodePath(rawPath);
        String payloadHash = body == null ? SigV4.EMPTY_SHA256 : SigV4.sha256Hex(body);
        String amzDate = ZonedDateTime.now(ZoneOffset.UTC).format(AMZ_DATE);
        String host = endpoint.getHost() + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());

        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("host", host);
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", amzDate);
        String auth = SigV4.authorization(method, path, "", signed, payloadHash,
                accessKey, secretKey, region, "s3", amzDate);

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint + path))
                .timeout(Duration.ofSeconds(30))
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", auth)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            req.header("Content-Type", contentType);
        }
        try {
            return http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new StorageException(method + " " + rawPath + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException(method + " " + rawPath + " interrupted", e);
        }
    }

    public static class StorageException extends RuntimeException {
        StorageException(String what, HttpResponse<byte[]> r) {
            super(what + " -> HTTP " + r.statusCode() + ": " + snippet(r.body()));
        }

        private static String snippet(byte[] body) {
            String s = new String(body, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
            return s.length() > 300 ? s.substring(0, 300) + "..." : s;
        }

        StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
