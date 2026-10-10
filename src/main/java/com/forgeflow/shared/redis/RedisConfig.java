package com.forgeflow.shared.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One Redis client for the whole app, when REDIS_URL is set.
 *
 * Three things use it: rate limits (token buckets), chat session locks, and
 * the preview Logs Stream (buffer + fan-out). With it, any number of API
 * instances behave like one. Without it, each falls back to memory - correct
 * for a single instance, which is what the free Render deploy runs.
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("!'${forgeflow.ratelimit.redis-url:}'.isBlank()")
    RespClient redis(@Value("${forgeflow.ratelimit.redis-url}") String url) {
        log.info("Redis configured: rate limits, session locks and preview logs are shared across instances");
        return new RespClient(url, 16, 2000);
    }
}
