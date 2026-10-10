package com.forgeflow.shared.storage;

import java.util.Optional;

/**
 * Blob storage: bytes under a key. The spec's MinIO box. Present only when
 * {@code forgeflow.storage.files=s3}; otherwise file contents stay in
 * Postgres and nothing asks for one of these.
 */
public interface ObjectStore {

    void put(String key, byte[] content, String contentType);

    Optional<byte[]> get(String key);

    void delete(String key);

    /** Where it points, for logs and health: "s3://bucket @ http://minio:9000". */
    String describe();
}
