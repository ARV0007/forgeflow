package com.forgeflow.shared.redis;

import com.forgeflow.support.RedisTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RespClientTest {

    private String key;

    @BeforeEach
    void setUp() {
        key = "ff:test:" + UUID.randomUUID();
    }

    // ------------------------------------------------ wire format, no Redis

    @Test
    void lengthsAreInBytesNotCharacters() {
        String encoded = new String(RespClient.encode("SET", "k", "héllo"), StandardCharsets.UTF_8);
        // "héllo" is 5 characters but 6 bytes in UTF-8.
        assertThat(encoded).isEqualTo("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$6\r\nhéllo\r\n");
    }

    @Test
    void everyReplyTypeParses() throws IOException {
        assertThat(read("+OK\r\n")).isEqualTo("OK");
        assertThat(read(":42\r\n")).isEqualTo(42L);
        assertThat(read("$5\r\nhello\r\n")).isEqualTo("hello");
        assertThat(read("$-1\r\n")).isNull();
        assertThat(read("*2\r\n:1\r\n$1\r\nx\r\n")).isEqualTo(List.of(1L, "x"));
        assertThat(read("-ERR wrong\r\n")).isInstanceOf(RespClient.RedisError.class);
        assertThatThrownBy(() -> read("$5\r\nhel")).isInstanceOf(IOException.class);
    }

    private static Object read(String wire) throws IOException {
        return RespClient.read(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void urlsWithCredentialsAndTlsAreAccepted() {
        new RespClient("rediss://default:p%40ss@cache.example.com:6380/2", 1, 100).close();
        assertThatThrownBy(() -> new RespClient("http://x", 1, 100)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------- against Redis

    @Test
    void talksToARealRedis() throws IOException {
        assumeTrue(RedisTestSupport.available(), "no Redis at " + RedisTestSupport.URL);
        try (RespClient redis = new RespClient(RedisTestSupport.URL, 2, 2000)) {
            assertThat(redis.call("PING")).isEqualTo("PONG");
            assertThat(redis.call("SET", key, "héllo wörld", "PX", "10000")).isEqualTo("OK");
            assertThat(redis.call("GET", key)).isEqualTo("héllo wörld");
            assertThat(redis.call("GET", key + ":missing")).isNull();
            redis.call("DEL", key);
            assertThat(redis.call("INCR", key)).isEqualTo(1L);
            redis.call("DEL", key);
        }
    }

    @Test
    void anErrorReplyLeavesTheConnectionUsable() throws IOException {
        assumeTrue(RedisTestSupport.available(), "no Redis at " + RedisTestSupport.URL);
        try (RespClient redis = new RespClient(RedisTestSupport.URL, 1, 2000)) {
            assertThatThrownBy(() -> redis.call("NOT_A_COMMAND")).isInstanceOf(RespClient.RedisError.class);
            assertThat(redis.call("PING")).isEqualTo("PONG");
        }
    }

    @Test
    void anUnreachableRedisIsAnIOExceptionNotAHang() {
        try (RespClient redis = new RespClient("redis://127.0.0.1:1", 1, 500)) {
            assertThatThrownBy(() -> redis.call("PING")).isInstanceOf(IOException.class);
        }
    }
}
