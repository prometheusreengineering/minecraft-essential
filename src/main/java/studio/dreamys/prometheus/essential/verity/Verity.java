package studio.dreamys.prometheus.essential.verity;

// Vendored verbatim from prometheusreengineering/verity (verity-api, protocol 1). Update by copying, don't edit.

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * Entry point mods use to report test results to Verity.
 *
 * <p>Every method is a cheap no-op when the game was not launched by Verity, so a mod can call these
 * unconditionally and ship with this class shaded (relocation is fine). The API only uses the JDK, works on
 * Java 8+, and does not touch any Minecraft or mod-loader class, so it behaves identically on every
 * version and loader.
 *
 * <pre>{@code
 * Verity.test("mixins-applied", () -> {
 *     if (!MyMod.patched) throw new AssertionError("patch not applied");
 * });
 * Verity.finish(); // tells Verity this client is done; Verity then stops the game
 * }</pre>
 *
 * <p>A client counts as passed when it calls {@link #finish()} after reporting no failures. Crashes, exits
 * before {@code finish()}, and timeouts are failures.
 */
public final class Verity {
    /** System property carrying the localhost port of Verity's hook server. */
    public static final String PROP_PORT = "verity.port";
    /** System property carrying the per-client secret token. */
    public static final String PROP_TOKEN = "verity.token";
    /** System property carrying the target id, e.g. {@code 1.20.1-fabric}. */
    public static final String PROP_TARGET = "verity.target";
    /** Protocol version spoken by this API. */
    public static final int PROTOCOL = 1;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Object LOCK = new Object();
    private static final int MAX_ATTACHMENT = 8 * 1024 * 1024;

    private static Socket socket;
    private static OutputStream out;
    private static BufferedReader in;
    private static boolean broken;
    private static boolean finished;

    private Verity() {
    }

    /** @return {@code true} when this game instance was started by Verity. */
    public static boolean isActive() {
        return System.getProperty(PROP_PORT) != null && System.getProperty(PROP_TOKEN) != null;
    }

    /** @return the Verity target id (e.g. {@code 1.20.1-fabric}), or {@code null} when not under Verity. */
    public static String target() {
        return System.getProperty(PROP_TARGET);
    }

    /** Records a passed check. */
    public static void pass(String name) {
        send("{\"type\":\"pass\",\"name\":" + quote(name) + "}");
    }

    /** Records a failed check. */
    public static void fail(String name, String message) {
        send("{\"type\":\"fail\",\"name\":" + quote(name) + ",\"message\":" + quote(message) + "}");
    }

    /** Records a failed check caused by {@code error}, including its stack trace. */
    public static void fail(String name, Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        send("{\"type\":\"fail\",\"name\":" + quote(name) + ",\"message\":" + quote(String.valueOf(error))
                + ",\"detail\":" + quote(sw.toString()) + "}");
    }

    /**
     * Runs {@code body} as a named check: passes if it returns normally, fails if it throws.
     *
     * @return whether the check passed
     */
    public static boolean test(String name, Runnable body) {
        try {
            body.run();
            pass(name);
            return true;
        } catch (Throwable t) {
            fail(name, t);
            return false;
        }
    }

    /** Adds a line to this client's Verity log. */
    public static void log(String message) {
        send("{\"type\":\"log\",\"message\":" + quote(message) + "}");
    }

    /**
     * Attaches a small file (screenshot, dump, ...) to this client's report. Attachments above 8 MiB are
     * rejected.
     */
    public static void attach(String name, byte[] data) {
        if (data == null || data.length > MAX_ATTACHMENT) {
            log("attachment '" + name + "' skipped: " + (data == null ? "null" : data.length + " bytes > 8 MiB"));
            return;
        }
        send("{\"type\":\"attach\",\"name\":" + quote(name) + ",\"data\":\"" + Base64.encode(data) + "\"}");
    }

    /**
     * Tells Verity this client has finished its checks. Verity marks the client passed (if no check failed)
     * and shuts the game down. Blocks briefly until Verity acknowledges, so nothing reported before this
     * call is lost.
     */
    public static void finish() {
        synchronized (LOCK) {
            if (finished) return;
            finished = true;
            if (send("{\"type\":\"finish\"}") && in != null) {
                try {
                    socket.setSoTimeout(5000);
                    in.readLine();
                } catch (IOException ignored) {
                    // Verity may already be stopping the process.
                }
            }
        }
    }

    /** Test hook: drops the connection and state. */
    static void reset() {
        synchronized (LOCK) {
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
            out = null;
            in = null;
            broken = false;
            finished = false;
        }
    }

    private static boolean send(String json) {
        if (!isActive()) return false;
        synchronized (LOCK) {
            if (broken) return false;
            try {
                if (out == null) connect();
                out.write((json + "\n").getBytes(UTF8));
                out.flush();
                return true;
            } catch (IOException e) {
                broken = true;
                System.err.println("[Verity] lost connection to Verity hook server: " + e);
                return false;
            }
        }
    }

    private static void connect() throws IOException {
        int port = Integer.parseInt(System.getProperty(PROP_PORT).trim());
        Socket s = new Socket(Proxy.NO_PROXY);
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        socket = s;
        out = s.getOutputStream();
        in = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF8));
        String hello = "{\"type\":\"hello\",\"token\":" + quote(System.getProperty(PROP_TOKEN))
                + ",\"protocol\":" + PROTOCOL + ",\"java\":" + quote(System.getProperty("java.version")) + "}";
        out.write((hello + "\n").getBytes(UTF8));
        out.flush();
    }

    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** Java 8 has java.util.Base64, but older shading setups sometimes strip it; keep a tiny encoder here. */
    static final class Base64 {
        private static final char[] ALPHABET =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

        static String encode(byte[] data) {
            StringBuilder b = new StringBuilder((data.length + 2) / 3 * 4);
            for (int i = 0; i < data.length; i += 3) {
                int n = (data[i] & 0xff) << 16;
                if (i + 1 < data.length) n |= (data[i + 1] & 0xff) << 8;
                if (i + 2 < data.length) n |= data[i + 2] & 0xff;
                b.append(ALPHABET[(n >> 18) & 63]).append(ALPHABET[(n >> 12) & 63]);
                b.append(i + 1 < data.length ? ALPHABET[(n >> 6) & 63] : '=');
                b.append(i + 2 < data.length ? ALPHABET[n & 63] : '=');
            }
            return b.toString();
        }
    }
}
