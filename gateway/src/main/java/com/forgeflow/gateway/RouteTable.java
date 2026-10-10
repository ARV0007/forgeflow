package com.forgeflow.gateway;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Which service a path goes to. Prefixes are matched segment by segment, with
 * "*" standing for exactly one segment - so "/api/v1/projects/*&#47;chat/"
 * matches any project's chat. First match wins, so order specific to general.
 *
 * A service may have several instances ("http://api-1:8081,http://api-2:8081").
 * Requests take turns (round robin, one counter per service), and a request
 * whose instance refuses the connection moves on to the next - see
 * {@link #attemptOrder}. That only works because the API is stateless: chat
 * locks, preview logs and rate limits live in Redis, so any instance can
 * answer any request. (In Kubernetes the Service does the balancing and the
 * list is one URL; this is for docker compose, and for showing the idea.)
 */
public class RouteTable {

    public record Match(String prefix, String service, List<URI> upstreams) {
    }

    private record Compiled(String prefix, String[] parts, boolean trailingSlash, String service, List<URI> upstreams) {
    }

    private final List<Compiled> routes = new ArrayList<>();
    private final Map<String, AtomicInteger> turns = new HashMap<>();

    public RouteTable(List<GatewayProperties.Route> routes, Map<String, String> upstreams) {
        for (GatewayProperties.Route r : routes) {
            String url = upstreams.get(r.service());
            if (url == null) {
                throw new IllegalArgumentException("route " + r.prefix() + " names unknown service " + r.service());
            }
            String p = r.prefix();
            List<URI> instances = Arrays.stream(url.split(",")).map(String::trim).filter(u -> !u.isEmpty())
                    .map(URI::create).toList();
            this.routes.add(new Compiled(p, split(p), p.endsWith("/"), r.service(), instances));
            turns.putIfAbsent(r.service(), new AtomicInteger());
        }
    }

    public Match match(String path) {
        String[] parts = split(path);
        for (Compiled r : routes) {
            if (matches(r, parts, path)) {
                return new Match(r.prefix(), r.service(), r.upstreams());
            }
        }
        return null;
    }

    /** Every instance of the matched service, starting with the one whose turn it is. */
    public List<URI> attemptOrder(Match m) {
        List<URI> all = m.upstreams();
        if (all.size() == 1) {
            return all;
        }
        int start = Math.floorMod(turns.get(m.service()).getAndIncrement(), all.size());
        List<URI> order = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            order.add(all.get((start + i) % all.size()));
        }
        return order;
    }

    public List<Match> all() {
        return routes.stream().map(r -> new Match(r.prefix(), r.service(), r.upstreams())).toList();
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
