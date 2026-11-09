package studio.dreamys.prometheus.essential.replay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * A recorded session (the record job's {@code recording.jsonl}: Essential's own packet log, filtered by the patch's
 * {@code verity/Recording}), split into what the server sent on its own and what it sent in answer to a request.
 */
final class Session {
    /** A packet as recorded: its name relative to {@code gg.essential.connectionmanager.common.packet}, and its JSON. */
    record Packet(String name, String json) {}

    final int protocolVersion;
    /** Server packets nobody asked for, in recorded order. */
    final List<Packet> pushes;
    /** Recorded replies by request name and canonical payload. */
    private final Map<String, List<Packet>> replies;
    /** Recorded replies by request name only, for requests recorded once under that name. */
    private final Map<String, List<Packet>> repliesByName;

    private Session(int protocolVersion, List<Packet> pushes, Map<String, List<Packet>> replies,
                    Map<String, List<Packet>> repliesByName) {
        this.protocolVersion = protocolVersion;
        this.pushes = pushes;
        this.replies = replies;
        this.repliesByName = repliesByName;
    }

    static Session load(Path file) throws IOException {
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    static Session parse(List<String> lines) {
        int protocol = 12;
        List<Packet> pushes = new ArrayList<>();
        Map<String, String> requestKeys = new HashMap<>();
        Map<String, String> requestNames = new HashMap<>();
        Map<String, List<Packet>> replies = new LinkedHashMap<>();
        Map<String, Integer> requestsPerName = new HashMap<>();
        Map<String, List<Packet>> byName = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            String type = string(o, "type");
            if ("OPEN".equals(type)) {
                if (o.has("version")) protocol = o.get("version").getAsInt();
                continue;
            }
            String name = string(o, "name");
            if (name.isEmpty() || name.startsWith("connection.")) continue;
            String id = string(o, "id");
            JsonElement payload = o.has("payload") ? o.get("payload") : new JsonObject();
            if ("SEND".equals(type)) {
                if (id.isEmpty()) continue;
                requestKeys.put(id, key(name, payload));
                requestNames.put(id, name);
                requestsPerName.merge(name, 1, Integer::sum);
            } else if ("RECV".equals(type)) {
                Packet packet = new Packet(name, payload.toString());
                String request = id.isEmpty() ? null : requestKeys.get(id);
                if (request == null) {
                    pushes.add(packet);
                } else {
                    replies.computeIfAbsent(request, k -> new ArrayList<>()).add(packet);
                    byName.computeIfAbsent(requestNames.get(id), k -> new ArrayList<>()).add(packet);
                }
            }
        }
        byName.keySet().removeIf(name -> requestsPerName.getOrDefault(name, 0) != 1);
        return new Session(protocol, pushes, replies, byName);
    }

    /**
     * The recorded replies to a request: the one with the same name and payload, or else, if the recording has exactly
     * one request of that name, that one's (a client may ask for a slightly different set, e.g. of legacy cosmetics).
     */
    Optional<List<Packet>> repliesTo(String name, String json) {
        List<Packet> exact = replies.get(key(name, JsonParser.parseString(json)));
        if (exact != null) return Optional.of(exact);
        return Optional.ofNullable(repliesByName.get(name));
    }

    Set<String> requestNames() {
        Set<String> names = new HashSet<>();
        for (String key : replies.keySet()) names.add(key.substring(0, key.indexOf(' ')));
        return names;
    }

    static String key(String name, JsonElement payload) {
        return name + " " + canonical(payload);
    }

    /** JSON with object keys sorted, and arrays of plain values sorted (Essential sends sets as arrays). */
    static String canonical(JsonElement e) {
        if (e == null || e.isJsonNull()) return "null";
        if (e.isJsonPrimitive()) return e.toString();
        if (e.isJsonObject()) {
            StringBuilder sb = new StringBuilder("{");
            TreeMap<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) sorted.put(entry.getKey(), entry.getValue());
            boolean first = true;
            for (Map.Entry<String, JsonElement> entry : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(new JsonPrimitive(entry.getKey())).append(':').append(canonical(entry.getValue()));
            }
            return sb.append('}').toString();
        }
        JsonArray array = e.getAsJsonArray();
        List<String> items = new ArrayList<>();
        boolean plain = true;
        for (JsonElement item : array) {
            items.add(canonical(item));
            plain &= item.isJsonPrimitive();
        }
        if (plain) items.sort(Comparator.naturalOrder());
        return "[" + String.join(",", items) + "]";
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
