package com.forgeflow.shared.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the limiter: Redis when REDIS_URL is set, in-memory otherwise.
 * The filter itself is created in SecurityConfig (see RateLimitFilter for why).
 */
@Configuration
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    @Bean(destroyMethod = "")
    RateLimiter rateLimiter(@Value("${forgeflow.ratelimit.redis-url:}") String redisUrl) {
        InMemoryRateLimiter local = new InMemoryRateLimiter();
        if (redisUrl.isBlank()) {
            log.info("Rate limiting in memory (no REDIS_URL) - correct for a single instance");
            return local;
        }
        log.info("Rate limiting in Redis, falling back to in-memory if it is unreachable");
        return new RedisRateLimiter(new RespClient(redisUrl, 8, 2000), local, "ff:rl:");
    }

    @Bean
    RateLimitRules rateLimitRules(@Value("${forgeflow.ratelimit.auth-per-minute:10}") int auth,
                                  @Value("${forgeflow.ratelimit.ai-per-minute:6}") int ai,
                                  @Value("${forgeflow.ratelimit.mcp-per-minute:30}") int mcp,
                                  @Value("${forgeflow.ratelimit.invites-per-hour:20}") int invites,
                                  @Value("${forgeflow.ratelimit.api-per-minute:300}") int api) {
        return new RateLimitRules(auth, ai, mcp, invites, api);
    }
}
