package com.forgeflow.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * @param upstreams service name -> base URL
 * @param routes    path prefix -> service name; first match wins
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(String jwtSecret, Map<String, String> upstreams, List<Route> routes) {

    public record Route(String prefix, String service) {
    }
}
