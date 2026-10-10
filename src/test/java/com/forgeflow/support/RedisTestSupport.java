package com.forgeflow.support;

import java.net.InetSocketAddress;
import java.net.Socket;

/** Redis-backed tests run where Redis is (CI has a service container) and skip where it isn't. */
public final class RedisTestSupport {

    public static final String URL = System.getenv().getOrDefault("REDIS_TEST_URL", "redis://localhost:6379");

    private RedisTestSupport() {
    }

    public static boolean available() {
        java.net.URI uri = java.net.URI.create(URL);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 6379), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
