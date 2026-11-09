package studio.dreamys.prometheus.essential.replay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Stands in for Essential's connection manager: accepts any client, sends it the recorded session, and answers its
 * requests with the recorded replies. Nothing is forwarded anywhere.
 */
final class ReplayServer extends WebSocketServer {
    /** Essential closes the connection when it hears no keep-alive for 60 s. */
    static final long KEEP_ALIVE_SECONDS = 20;

    private final Session session;
    private final Consumer<String> unanswered;
    private final Map<WebSocket, Peer> peers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cm-replay-keepalive");
        t.setDaemon(true);
        return t;
    });
    final List<String> clients = new CopyOnWriteArrayList<>();

    ReplayServer(InetSocketAddress address, Session session, Consumer<String> unanswered) {
        super(address);
        this.session = session;
        this.unanswered = unanswered;
        setReuseAddr(true);
    }

    /** Per-connection type ids, which each side assigns for the packets it sends. */
    private static final class Peer {
        final Map<Integer, String> incoming = new ConcurrentHashMap<>();
        final Map<String, Integer> outgoing = new ConcurrentHashMap<>();
        int nextId = 1;
    }

    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft, ClientHandshake request)
            throws InvalidDataException {
        ServerHandshakeBuilder response = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
        // Essential parses this without a null check, and speaks the version the recording was made with.
        response.put("Essential-Protocol-Version", Integer.toString(session.protocolVersion));
        return response;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(handshake.getFieldValue("Essential-User-Name") + " " + handshake.getFieldValue("Essential-Minecraft-Version")
                + " " + handshake.getFieldValue("Essential-Minecraft-Modloader"));
        peers.put(conn, new Peer());
        for (Session.Packet p : session.pushes) send(conn, p.name(), "", p.json());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        peers.remove(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // Essential only sends binary frames.
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer bytes) {
        Peer peer = peers.get(conn);
        if (peer == null) return;
        Frame frame = Frame.decode(bytes);
        if (frame.typeId() == Frame.REGISTER) {
            JsonObject o = JsonParser.parseString(frame.json()).getAsJsonObject();
            peer.incoming.put(o.get("b").getAsInt(), o.get("a").getAsString());
            return;
        }
        String name = peer.incoming.get(frame.typeId());
        if (name == null || name.equals(Frame.KEEP_ALIVE) || frame.uniqueId().isEmpty()) return;
        Optional<List<Session.Packet>> replies = session.repliesTo(name, frame.json());
        if (replies.isEmpty()) {
            unanswered.accept(name + " " + frame.json());
            return;
        }
        for (Session.Packet p : replies.get()) send(conn, p.name(), frame.uniqueId(), p.json());
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("cm-replay: " + (conn == null ? "server" : conn.getRemoteSocketAddress()) + ": " + ex);
    }

    @Override
    public void onStart() {
        keepAlive.scheduleAtFixedRate(() -> {
            for (WebSocket conn : peers.keySet()) {
                if (conn.isOpen()) send(conn, Frame.KEEP_ALIVE, UUID.randomUUID().toString(), "{}");
            }
        }, KEEP_ALIVE_SECONDS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void stop(int timeout) throws InterruptedException {
        keepAlive.shutdownNow();
        super.stop(timeout);
    }

    private void send(WebSocket conn, String name, String uniqueId, String json) {
        Peer peer = peers.get(conn);
        if (peer == null || !conn.isOpen()) return;
        synchronized (peer) {
            Integer id = peer.outgoing.get(name);
            if (id == null) {
                id = peer.nextId++;
                peer.outgoing.put(name, id);
                JsonObject register = new JsonObject();
                register.addProperty("a", name);
                register.addProperty("b", id);
                conn.send(new Frame(Frame.REGISTER, "", register.toString()).encode());
            }
            conn.send(new Frame(id, uniqueId, json).encode());
        }
    }
}
