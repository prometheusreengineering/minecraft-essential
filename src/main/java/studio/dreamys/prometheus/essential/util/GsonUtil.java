package studio.dreamys.prometheus.essential.util;

import gg.essential.lib.gson.Gson;
import gg.essential.lib.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class GsonUtil {
    private static final Gson GSON = new Gson();

    /**
     * Serializes with 4-space indent (one element per line, no trailing newline)
     */
    public static void writePretty(Path path, Object src, Type type) throws IOException {
        try (JsonWriter jsonWriter = new JsonWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            writePretty(jsonWriter, src, type);
        }
    }

    /** Same output as {@link #writePretty(Path, Object, Type)}, as a string. */
    public static String toPrettyString(Object src, Type type) throws IOException {
        StringWriter writer = new StringWriter();
        try (JsonWriter jsonWriter = new JsonWriter(writer)) {
            writePretty(jsonWriter, src, type);
        }
        return writer.toString();
    }

    private static void writePretty(JsonWriter jsonWriter, Object src, Type type) {
        jsonWriter.setIndent("    "); // GsonBuilder.setPrettyPrinting is 2 space by default
        GSON.toJson(src, type, jsonWriter);
    }
}