package studio.dreamys.prometheus.essential.replay;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * {@code java -jar cm-replay.jar <recording.jsonl> [--port 47801] [--join-port 47802] [--unanswered <file>]}
 *
 * <p>Serves the recording on {@code ws://127.0.0.1:<port>/} and Mojang's join endpoint on
 * {@code http://127.0.0.1:<join-port>}, both on loopback only, until killed. See verity-replay.json for how the game
 * is pointed at them.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Path recording = null;
        int port = 47801;
        int joinPort = 47802;
        Path unansweredFile = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--join-port" -> joinPort = Integer.parseInt(args[++i]);
                case "--unanswered" -> unansweredFile = Path.of(args[++i]);
                default -> recording = Path.of(args[i]);
            }
        }
        if (recording == null) {
            System.err.println("usage: cm-replay <recording.jsonl> [--port N] [--join-port N] [--unanswered FILE]");
            System.exit(2);
        }
        Session session = Session.load(recording);
        InetAddress loopback = InetAddress.getLoopbackAddress();

        Set<String> seen = ConcurrentHashMap.newKeySet();
        Path out = unansweredFile;
        ReplayServer server = new ReplayServer(new InetSocketAddress(loopback, port), session, request -> {
            // One line per distinct request; the payload is cut, since a legacy request lists hundreds of ids.
            String line = request.length() > 300 ? request.substring(0, 300) + "..." : request;
            if (!seen.add(line)) return;
            System.out.println("cm-replay: no recorded answer for " + line);
            if (out != null) append(out, line);
        });
        JoinStub join = new JoinStub(new InetSocketAddress(loopback, joinPort));
        join.start();
        server.start();
        System.out.println("cm-replay: protocol " + session.protocolVersion + ", " + session.pushes.size()
                + " pushes, replies for " + session.requestNames() + "; ws://127.0.0.1:" + port
                + "/, join stub http://127.0.0.1:" + join.port());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("cm-replay: served " + server.clients.size() + " connections");
            join.stop();
        }));
        new CountDownLatch(1).await();
    }

    private static synchronized void append(Path file, String line) {
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("cm-replay: cannot write " + file + ": " + e);
        }
    }
}
