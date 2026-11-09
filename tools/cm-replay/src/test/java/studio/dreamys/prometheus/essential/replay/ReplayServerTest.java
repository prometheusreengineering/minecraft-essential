package studio.dreamys.prometheus.essential.replay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Talks to the server the way Essential's {@code Connection} and {@code ConnectionCodec} do. */
class ReplayServerTest {
    private ReplayServer server;
    private final List<String> unanswered = new CopyOnWriteArrayList<>();
    private int port;

    @BeforeEach
    void start() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        server = new ReplayServer(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                Session.parse(SessionTest.RECORDING), unanswered::add);
        server.start();
        // WebSocketServer.start() returns before it listens.
        for (int i = 0; i < 100; i++) {
            try (java.net.Socket ignored = new java.net.Socket(InetAddress.getLoopbackAddress(), port)) {
                break;
            } catch (java.io.IOException e) {
                Thread.sleep(50);
            }
        }
    }

    @AfterEach
    void stop() throws Exception {
        server.stop(1000);
    }

    /** A client that keeps the server's type ids and queues decoded packets as "name uniqueId json". */
    static final class Client extends WebSocketClient {
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        final Map<Integer, String> types = new ConcurrentHashMap<>();
        volatile String protocol;
        private int nextId;

        Client(URI uri) {
            super(uri, Map.of("Essential-User-Name", "Verity", "Essential-User-UUID", "00000000-0000-0000-0000-000000000001",
                    "Essential-Max-Protocol-Version", "12"));
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            protocol = handshake.getFieldValue("Essential-Protocol-Version");
        }

        @Override
        public void onMessage(String message) {}

        @Override
        public void onMessage(ByteBuffer bytes) {
            Frame f = Frame.decode(bytes);
            if (f.typeId() == Frame.REGISTER) {
                JsonObject o = JsonParser.parseString(f.json()).getAsJsonObject();
                types.put(o.get("b").getAsInt(), o.get("a").getAsString());
            } else {
                received.add(types.get(f.typeId()) + " " + f.uniqueId() + " " + f.json());
            }
        }

        void sendPacket(String name, String uniqueId, String json) {
            int id = ++nextId;
            JsonObject register = new JsonObject();
            register.addProperty("a", name);
            register.addProperty("b", id);
            send(new Frame(Frame.REGISTER, "", register.toString()).encode());
            send(new Frame(id, uniqueId, json).encode());
        }

        String next() throws InterruptedException {
            String s = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(s, "nothing received");
            return s;
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {}

        @Override
        public void onError(Exception ex) {}
    }

    private Client connect() throws Exception {
        Client c = new Client(URI.create("ws://127.0.0.1:" + port + "/v1"));
        assertTrue(c.connectBlocking(5, TimeUnit.SECONDS));
        return c;
    }

    @Test
    void sendsTheRecordedProtocolAndPushesOnConnect() throws Exception {
        Client c = connect();
        try {
            assertEquals("11", c.protocol);
            assertEquals("cosmetic.ServerCosmeticTypesPopulatePacket  {\"a\":[1]}", c.next());
            assertEquals("cosmetic.outfit.ServerCosmeticOutfitPopulatePacket  {\"a\":[]}", c.next());
        } finally {
            c.closeBlocking();
        }
    }

    @Test
    void answersRequestsUnderTheLiveUniqueId() throws Exception {
        Client c = connect();
        try {
            c.next();
            c.next();
            c.sendPacket("cosmetic.ClientCosmeticRequestPacket", "live-1", "{\"a\":[\"A\",\"B\"],\"b\":[]}");
            assertEquals("cosmetic.ServerCosmeticsPopulatePacket live-1 {\"a\":[\"legacy\"]}", c.next());
        } finally {
            c.closeBlocking();
        }
    }

    @Test
    void servesEveryClientTheSameSession() throws Exception {
        Client a = connect();
        Client b = connect();
        try {
            for (Client c : List.of(a, b)) {
                assertTrue(c.next().startsWith("cosmetic.ServerCosmeticTypesPopulatePacket"));
            }
        } finally {
            a.closeBlocking();
            b.closeBlocking();
        }
    }

    @Test
    void reportsRequestsItHasNoAnswerFor() throws Exception {
        Client c = connect();
        try {
            c.sendPacket("chat.ClientChatChannelMessagesRetrievePacket", "live-2", "{\"a\":1}");
            c.sendPacket(Frame.KEEP_ALIVE, "k", "{}");
            for (int i = 0; i < 50 && unanswered.isEmpty(); i++) Thread.sleep(20);
            assertEquals(List.of("chat.ClientChatChannelMessagesRetrievePacket {\"a\":1}"), unanswered);
        } finally {
            c.closeBlocking();
        }
    }

    @Test
    void joinStubAcceptsJoinsOnly() throws Exception {
        JoinStub stub = new JoinStub(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        stub.start();
        try {
            HttpURLConnection join = (HttpURLConnection) URI.create("http://127.0.0.1:" + stub.port() + "/session/minecraft/join").toURL().openConnection();
            join.setRequestMethod("POST");
            join.setDoOutput(true);
            join.getOutputStream().write("{\"accessToken\":\"0\"}".getBytes());
            assertEquals(204, join.getResponseCode());
            HttpURLConnection other = (HttpURLConnection) URI.create("http://127.0.0.1:" + stub.port() + "/session/minecraft/profile/x").toURL().openConnection();
            assertEquals(404, other.getResponseCode());
        } finally {
            stub.stop();
        }
    }
}
