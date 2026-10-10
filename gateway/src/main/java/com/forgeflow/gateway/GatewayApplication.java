package com.forgeflow.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The spec's "Spring Cloud API Gateway" box: the one public entry point.
 *
 * Built on plain Spring MVC and java.net.http rather than Spring Cloud
 * Gateway - the routing, the streaming proxy and the edge checks are a few
 * hundred lines, and owning them keeps one thing visible that a framework
 * would hide: how a long-lived SSE stream passes through a proxy unbuffered.
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
