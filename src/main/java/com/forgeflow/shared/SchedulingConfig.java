package com.forgeflow.shared;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs (the nightly blob sweep). Off with forgeflow.scheduling.enabled=false. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "forgeflow.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
