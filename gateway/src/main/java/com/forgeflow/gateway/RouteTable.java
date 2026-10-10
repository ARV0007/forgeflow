package com.forgeflow.gateway;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Which service a path goes to. Prefixes are matched segment by segment, with
 * "*" standing for exactly one segment - so "/api/v1/projects/*&#47;chat/"
 * matches any project's chat. First match wins, so order specific to general.
 */
public class RouteTable {

    public record Match(String prefix, String service, URI upstream) {
    }

    private record Compiled(String prefix, String[] parts, boolean trailingSlash, String service, URI upstream) {
    }

    private final List<Compiled> routes = new ArrayList<>();

    public RouteTable(List<GatewayProperties.Route> routes, Map<String, String> upstreams) {
        for (GatewayProperties.Route r : routes) {
            String url = upstreams.get(r.service());
            if (url == null) {
                throw new IllegalArgumentException("route " + r.prefix() + " names unknown service " + r.service());
            }
            String p = r.prefix();
            this.routes.add(new Compiled(p, split(p), p.endsWith("/"), r.service(), URI.create(url)));
        }
    }

    public Match match(String path) {
        String[] parts = split(path);
        for (Compiled r : routes) {
            if (matches(r, parts, path)) {
                return new Match(r.prefix(), r.service(), r.upstream());
            }
        }
        return null;
    }

    public List<Match> all() {
        return routes.stream().map(r -> new Match(r.prefix(), r.service(), r.upstream())).toList();
    }

    private static boolean matches(Compiled r, String[] path, String rawPath) {
        if (r.parts().length == 0) {
            return true;                                   // "/" matches everything
        }
        if (path.length < r.parts().length) {
            return false;
        }
        for (int i = 0; i < r.parts().length; i++) {
            if (!r.parts()[i].equals("*") && !r.parts()[i].equals(path[i])) {
                return false;
            }
        }
        // "/p/" must not match "/pricing": a trailing slash means "a directory".
        return !r.trailingSlash() || path.length > r.parts().length || rawPath.endsWith("/");
    }

    private static String[] split(String path) {
        return java.util.Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }
}
