package com.forgeflow.shared.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code forgeflow.storage.files=s3} turns file contents into objects in a
 * bucket (MinIO in docker-compose.full.yml and deploy/k8s). Default
 * {@code postgres}: contents stay in a TEXT column and no ObjectStore exists.
 */
@Configuration
class StorageConfig {

    @Bean
    @ConditionalOnProperty(name = "forgeflow.storage.files", havingValue = "s3")
    ObjectStore objectStore(@Value("${forgeflow.storage.s3.endpoint}") String endpoint,
                            @Value("${forgeflow.storage.s3.region:us-east-1}") String region,
                            @Value("${forgeflow.storage.s3.bucket:forgeflow-files}") String bucket,
                            @Value("${forgeflow.storage.s3.access-key}") String accessKey,
                            @Value("${forgeflow.storage.s3.secret-key}") String secretKey) {
        S3ObjectStore store = new S3ObjectStore(endpoint, region, bucket, accessKey, secretKey);
        store.ensureBucket();
        return store;
    }
}
