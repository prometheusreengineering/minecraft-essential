package studio.dreamys.prometheus.essential.replay;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Stands in for Mojang's session server ({@code minecraft.api.session.host}). Before connecting, Essential announces the
 * connection there with the player's access token; Essential's real server then checks that with Mojang, but the
 * replay server doesn't, so the replayed clients need no session. Answers {@code POST /session/minecraft/join} with
 * 204 like Mojang does, and everything else with 404.
 *
 * <p>TODO: replace with a jsonproxy {@code respond} rule once jsonproxy is published.
 */
final class JoinStub {
    private final HttpServer server;

    JoinStub(InetSocketAddress address) throws IOException {
        server = HttpServer.create(address, 64);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                boolean join = "POST".equals(exchange.getRequestMethod())
                        && "/session/minecraft/join".equals(exchange.getRequestURI().getPath());
                exchange.sendResponseHeaders(join ? 204 : 404, -1);
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "cm-replay-join");
            t.setDaemon(true);
            return t;
        }));
    }

    void start() {
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }
}
