package studio.dreamys.prometheus.essential.util;

import gg.essential.lib.gson.Gson;
import gg.essential.lib.gson.JsonIOException;
import gg.essential.lib.gson.JsonSyntaxException;
import gg.essential.lib.gson.reflect.TypeToken;
import gg.essential.lib.gson.stream.JsonWriter;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public class GsonUtil {
    private GsonUtil() {}
    private static final Gson GSON = new Gson();
    private static final @NotNull Type STRING_SET_TYPE = new TypeToken<LinkedHashSet<String>>() {}.getType();

    /**
     * Serializes with 4-space indent
     */
    public static void writePretty(@NotNull Path path, Object src, @NotNull Type type) throws IOException {
        try (Writer fileWriter = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
             JsonWriter jsonWriter = new JsonWriter(fileWriter)) {
            jsonWriter.setIndent("    "); // GsonBuilder.setPrettyPrinting is 2 space by default
            GSON.toJson(src, type, jsonWriter);
        }
    }

    /**
     * Parses a JSON array of strings (e.g. {@code ["a", "b", "c"]}) into a String[].
     * Ensures no duplicate items, and gracefully returns an empty array on error.
     */
    public static @NotNull String @NotNull [] toStringArray(@NotNull Reader reader) {
        try {
            return Objects.requireNonNull(GSON.<Set<String>>fromJson(reader, STRING_SET_TYPE)).toArray(new String[0]);
        } catch (NullPointerException | JsonIOException | JsonSyntaxException e) {
            return new String[0]; // empty array, will be repopulated next launch (+ when new cosmetics come in from essential)
        }
    }
}
