package com.forgeflow.shared.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Against a real S3 API: MinIO in CI (S3_TEST_ENDPOINT); skipped without one. */
@EnabledIfEnvironmentVariable(named = "S3_TEST_ENDPOINT", matches = ".+")
class S3ObjectStoreIntegrationTest {

    private final String endpoint = System.getenv("S3_TEST_ENDPOINT");
    private final String access = System.getenv().getOrDefault("S3_TEST_ACCESS_KEY", "minioadmin");
    private final String secret = System.getenv().getOrDefault("S3_TEST_SECRET_KEY", "minioadmin");

    @Test
    void putGetDeleteRoundTripWithASignedRequest() {
        S3ObjectStore store = new S3ObjectStore(endpoint, "us-east-1", "it-" + UUID.randomUUID(), access, secret);
        store.ensureBucket();
        store.ensureBucket();                                  // second time: already exists, not an error

        String key = "projects/7/blobs/a file with spaces & café.js";
        byte[] content = "console.log('hello from object storage');".getBytes(StandardCharsets.UTF_8);
        store.put(key, content, "text/plain; charset=utf-8");

        assertThat(store.get(key)).hasValueSatisfying(b -> assertThat(b).isEqualTo(content));
        assertThat(store.get("projects/7/blobs/nothing-here")).isEmpty();

        store.delete(key);
        assertThat(store.get(key)).isEmpty();
        store.delete(key);                                     // deleting twice is fine
    }

    /**
     * Proof the signature is really checked - needs a server that checks it.
     * MinIO does (CI sets S3_TEST_STRICT); moto, handy for a laptop, doesn't.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "S3_TEST_STRICT", matches = "true")
    void aWrongSecretIsRefusedNotIgnored() {
        S3ObjectStore good = new S3ObjectStore(endpoint, "us-east-1", "it-" + UUID.randomUUID(), access, secret);
        good.ensureBucket();
        S3ObjectStore bad = new S3ObjectStore(endpoint, "us-east-1", "it-bad", access, secret + "x");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bad.put("k", new byte[]{1}, null))
                .isInstanceOf(S3ObjectStore.StorageException.class).hasMessageContaining("HTTP 403");
    }
}
