package com.forgeflow.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * @param upstreams service name -> base URL
 * @param routes    path prefix -> service name; first match wins
 * @param previewDomain   Kubernetes previews: requests for {token}.{previewDomain} ...
 * @param previewUpstream ... go to this URL, with {token} filled in (blank = no preview hosts)
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(String jwtSecret, Map<String, String> upstreams, List<Route> routes,
                                String previewDomain, String previewUpstream) {

    public record Route(String prefix, String service) {
    }
}
