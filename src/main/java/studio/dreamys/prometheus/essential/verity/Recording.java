package studio.dreamys.prometheus.essential.verity;

import gg.essential.lib.gson.JsonObject;
import gg.essential.lib.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Turns Essential's own packet log ({@code <global Essential dir>/infra-logs/<time>.log}, one JSON object per line:
 * {@code {"type": "SEND"|"RECV", "name": ..., "payload": ..., "id": ...}}, after an {@code OPEN} line with the protocol
 * version) into the recording that {@code tools/cm-replay} serves to the other Verity targets.
 *
 * <p>The log is the test account's whole session: chat, friends, profile and media too. Only what the cosmetics side of
 * Essential needs is kept, so nothing else about the account ends up in a CI artifact.
 */
final class Recording {
    /** The attachment name; the workflow looks for it in the record job's report. */
    static final String FILE = "recording.jsonl";
    /** Packet names (relative to {@code gg.essential.connectionmanager.common.packet}) kept in the recording. */
    private static final String[] KEEP_PREFIXES = {"cosmetic.", "wardrobe.", "features.ServerDisabledFeaturesPacket",
            "currency.", "coins.ServerCoinBundleOptionsPacket", "coins.ClientCoinBundleOptionsPacket"};
    /** The replay server negotiates its own type ids and sends its own keep-alives. */
    private static final String[] PER_CONNECTION = {"connection."};

    private Recording() {}

    static boolean keeps(String name) {
        if (name == null) return false;
        for (String p : PER_CONNECTION) if (name.startsWith(p)) return false;
        for (String p : KEEP_PREFIXES) if (name.startsWith(p)) return true;
        return false;
    }

    /**
     * Keeps the {@code OPEN} line, the kept client requests, and server packets that are either kept or answer a kept
     * request. Lines that aren't JSON objects are dropped.
     */
    @SuppressWarnings("deprecation") // the static parse methods don't exist in older Gson builds
    static List<String> filter(List<String> log) {
        List<String> out = new ArrayList<>();
        Set<String> keptRequests = new HashSet<>();
        JsonParser parser = new JsonParser();
        for (String line : log) {
            JsonObject o;
            try {
                o = parser.parse(line).getAsJsonObject();
            } catch (RuntimeException e) {
                continue;
            }
            String type = string(o, "type");
            String name = string(o, "name");
            String id = string(o, "id");
            if ("OPEN".equals(type)) {
                out.add(line);
            } else if ("SEND".equals(type) && keeps(name)) {
                if (!id.isEmpty()) keptRequests.add(id);
                out.add(line);
            } else if ("RECV".equals(type) && !name.startsWith("connection.")
                    && (keeps(name) || (!id.isEmpty() && keptRequests.contains(id)))) {
                out.add(line);
            }
        }
        return out;
    }

    /** The newest {@code .log} in Essential's {@code infra-logs} folder (older ones are gzipped), or null. */
    static Path newestLog(Path infraLogs) throws IOException {
        if (!Files.isDirectory(infraLogs)) return null;
        Path newest = null;
        try (Stream<Path> files = Files.list(infraLogs)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (!p.getFileName().toString().endsWith(".log")) continue;
                if (newest == null || Files.getLastModifiedTime(p).compareTo(Files.getLastModifiedTime(newest)) > 0) newest = p;
            }
        }
        return newest;
    }

    static byte[] record(Path log) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : filter(Files.readAllLines(log, StandardCharsets.UTF_8))) sb.append(line).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
