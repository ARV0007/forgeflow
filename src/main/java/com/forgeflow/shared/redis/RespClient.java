package com.forgeflow.shared.redis;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A minimal Redis client: RESP2 over a socket, with a small connection pool.
 *
 * Why not Lettuce or Jedis? This build can only use libraries already in the
 * local Maven cache, and neither is. But the protocol is small enough to read
 * in one sitting, and that is the point worth knowing anyway:
 *
 *   request   an array of bulk strings:  *3\r\n $3\r\nSET\r\n $1\r\nk\r\n $1\r\nv\r\n
 *   replies   +OK   -ERR msg   :42   $5\r\nhello   $-1 (nil)   *2\r\n...(array)
 *
 * Supports redis:// and rediss:// (TLS) URLs with an optional user, password
 * and database - the shapes Render, Upstash and Redis Cloud hand out.
 */
public class RespClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(RespClient.class);

    /** A "-ERR ..." reply. The connection is still healthy after one. */
    public static class RedisError extends RuntimeException {
        public RedisError(String message) {
            super(message);
        }
    }

    private final String host;
    private final int port;
    private final boolean tls;
    private final String username;
    private final String password;
    private final int database;
    private final int timeoutMs;
    private final BlockingQueue<Connection> idle;

    public RespClient(String url, int poolSize, int timeoutMs) {
        URI uri = URI.create(url);
        if (!"redis".equals(uri.getScheme()) && !"rediss".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Expected a redis:// or rediss:// URL");
        }
        this.host = uri.getHost();
        this.port = uri.getPort() > 0 ? uri.getPort() : 6379;
        this.tls = "rediss".equals(uri.getScheme());
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int colon = userInfo.indexOf(':');
            String user = colon < 0 ? "" : decode(userInfo.substring(0, colon));
            this.username = user.isEmpty() ? null : user;
            this.password = decode(colon < 0 ? userInfo : userInfo.substring(colon + 1));
        } else {
            this.username = null;
            this.password = null;
        }
        String path = uri.getPath();
        this.database = path == null || path.length() <= 1 ? 0 : Integer.parseInt(path.substring(1));
        this.timeoutMs = timeoutMs;
        this.idle = new ArrayBlockingQueue<>(poolSize);
    }

    /** Run one command. Throws IOException if Redis cannot be reached, RedisError for "-ERR". */
    public Object call(String... args) throws IOException {
        Connection c = idle.poll();
        if (c == null) {
            c = open();
        }
        try {
            Object reply = c.roundTrip(args);
            if (!idle.offer(c)) {
                c.close();          // pool full - more callers than slots right now
            }
            if (reply instanceof RedisError e) {
                throw e;
            }
            return reply;
        } catch (IOException | RuntimeException e) {
            if (!(e instanceof RedisError)) {
                c.close();          // never return a connection in an unknown state
            }
            throw e;
        }
    }

    /** A live pattern subscription. Close it to stop listening. */
    public interface Subscription extends Closeable {
        /** Wait until Redis has confirmed the subscription - messages published before that are not seen. */
        boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException;

        @Override
        void close();
    }

    /**
     * PSUBSCRIBE on a connection of its own, read by one virtual thread.
     *
     * A subscribed connection can do nothing else - Redis only sends it
     * messages from then on - so it never goes near the pool. If the
     * connection drops, it reconnects with backoff and subscribes again;
     * anything published in the gap is missed, which is why callers that
     * must not lose data keep it somewhere else too (PreviewLogs keeps a
     * Redis list and uses pub/sub only to say "there's a new line").
     *
     * @param onMessage (channel, payload), on the subscriber thread - keep it quick
     */
    public Subscription psubscribe(String pattern, BiConsumer<String, String> onMessage) {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Connection> current = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        Thread.ofVirtual().name("redis-psubscribe-" + pattern).start(() -> {
            long backoff = 500;
            while (running.get()) {
                Connection c = null;
                try {
                    c = open();
                    c.socket.setSoTimeout(0);        // a subscriber waits as long as it takes
                    current.set(c);
                    c.out.write(encode("PSUBSCRIBE", pattern));
                    c.out.flush();
                    while (running.get()) {
                        if (!(read(c.in) instanceof List<?> m) || m.isEmpty()) {
                            continue;
                        }
                        if ("psubscribe".equals(m.get(0))) {
                            backoff = 500;
                            ready.countDown();
                        } else if ("pmessage".equals(m.get(0)) && m.size() == 4) {
                            try {
                                onMessage.accept(String.valueOf(m.get(2)), String.valueOf(m.get(3)));
                            } catch (RuntimeException e) {
                                log.warn("redis subscriber for {} threw: {}", pattern, e.toString());
                            }
                        }
                    }
                } catch (IOException e) {
                    if (!running.get()) {
                        break;
                    }
                    log.warn("redis subscription {} lost ({}); retrying in {} ms", pattern, e.getMessage(), backoff);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    backoff = Math.min(backoff * 2, 10_000);
                } finally {
                    if (c != null) {
                        c.close();
                    }
                }
            }
        });
        return new Subscription() {
            @Override
            public boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
                return ready.await(timeout, unit);
            }

            @Override
            public void close() {
                running.set(false);
                Connection c = current.get();
                if (c != null) {
                    c.close();               // unblocks the reader
                }
            }
        };
    }

    @Override
    public void close() {
        Connection c;
        while ((c = idle.poll()) != null) {
            c.close();
        }
    }

    private Connection open() throws IOException {
        Socket socket = tls ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        // A hung Redis must not hang the request that asked it something.
        socket.setSoTimeout(timeoutMs);
        socket.setTcpNoDelay(true);
        Connection c = new Connection(socket);
        try {
            if (password != null) {
                Object r = username == null ? c.roundTrip("AUTH", password) : c.roundTrip("AUTH", username, password);
                if (r instanceof RedisError e) {
                    throw new IOException("Redis AUTH failed: " + e.getMessage());
                }
            }
            if (database != 0) {
                Object r = c.roundTrip("SELECT", Integer.toString(database));
                if (r instanceof RedisError e) {
                    throw new IOException("Redis SELECT failed: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            c.close();
            throw e;
        }
        return c;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ wire format

    private static final class Connection {

        private final Socket socket;
        private final OutputStream out;
        private final InputStream in;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
            this.in = new BufferedInputStream(socket.getInputStream());
        }

        Object roundTrip(String... args) throws IOException {
            out.write(encode(args));
            out.flush();
            return read(in);
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }

    public static byte[] encode(String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeAscii(buf, "*" + args.length + "\r\n");
        for (String a : args) {
            byte[] bytes = a.getBytes(StandardCharsets.UTF_8);
            // The length is in BYTES, not characters - the classic RESP bug.
            writeAscii(buf, "$" + bytes.length + "\r\n");
            buf.writeBytes(bytes);
            writeAscii(buf, "\r\n");
        }
        return buf.toByteArray();
    }

    /** Reads one reply: String, Long, null, List of replies, or a RedisError value. */
    public static Object read(InputStream in) throws IOException {
        int type = in.read();
        if (type < 0) {
            throw new IOException("Redis closed the connection");
        }
        String line = readLine(in);
        return switch (type) {
            case '+' -> line;
            case '-' -> new RedisError(line);
            case ':' -> Long.parseLong(line);
            case '$' -> {
                int len = Integer.parseInt(line);
                if (len < 0) {
                    yield null;
                }
                byte[] data = in.readNBytes(len);
                if (data.length < len) {
                    throw new IOException("Redis reply truncated");
                }
                readLine(in);                      // the trailing \r\n
                yield new String(data, StandardCharsets.UTF_8);
            }
            case '*' -> {
                int n = Integer.parseInt(line);
                if (n < 0) {
                    yield null;
                }
                List<Object> items = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    items.add(read(in));
                }
                yield items;
            }
            default -> throw new IOException("Unexpected RESP type byte: " + (char) type);
        };
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("Redis closed the connection mid-reply");
            }
            if (prev == '\r' && b == '\n') {
                byte[] bytes = buf.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            }
            buf.write(b);
            prev = b;
        }
    }

    private static void writeAscii(ByteArrayOutputStream buf, String s) {
        buf.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }
}
