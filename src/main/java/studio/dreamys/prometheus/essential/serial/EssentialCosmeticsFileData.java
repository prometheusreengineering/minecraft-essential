package studio.dreamys.prometheus.essential.serial;

import gg.essential.lib.gson.Gson;
import gg.essential.lib.gson.reflect.TypeToken;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Backing store for the legacy cosmetics list. Serializes as the bare Set's
 * {@code ["a", "b", "c"]} array via Gson.
 */
public final class EssentialCosmeticsFileData {
    private static final @NotNull Logger logger = Logger.getLogger("Prometheus - ECFD");
    private static final Gson GSON = new Gson();
    private static final Type SET_TYPE = new TypeToken<LinkedHashSet<String>>() {}.getType();
    private static final File COSMETICS_FILE =
            new File(EssentialCosmeticsManager.PROMETHEUS_ESSENTIAL_FOLDER.toFile(), "cosmetics.json");

    private final @NotNull Set<String> legacyCosmetics;

    private EssentialCosmeticsFileData(@NotNull Set<String> legacyCosmetics) {
        this.legacyCosmetics = legacyCosmetics;
    }

    private static final @NotNull EssentialCosmeticsFileData current;

    static {
        try {
            if (!COSMETICS_FILE.exists()) {
                Files.createDirectories(COSMETICS_FILE.getParentFile().toPath());
                try (InputStream in = EssentialCosmeticsFileData.class.getClassLoader()
                        .getResourceAsStream("cosmetics.json")) {
                    if (in == null) throw new IllegalStateException("Bundled cosmetics.json not found!");
                    Files.copy(in, COSMETICS_FILE.toPath());
                }
            }
            String json = new String(Files.readAllBytes(COSMETICS_FILE.toPath()), StandardCharsets.UTF_8);
            Set<String> set = GSON.fromJson(json, SET_TYPE);
            current = new EssentialCosmeticsFileData(set != null ? set : new LinkedHashSet<>());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // called by MixinCosmeticsManager
    public static @NotNull Set<@NotNull String> getCosmetics() {
        return current.legacyCosmetics;
    }

    /** Merges a cosmetic ID into the list, dumps it to file */
    public static boolean addCosmetic(@NotNull String id) {
        return addCosmetic(id, true);
    }

    private static boolean addCosmetic(@NotNull String id, boolean save) {
        if (current.legacyCosmetics.contains(id)) return false;
        current.legacyCosmetics.add(id);
        if (save) saveCosmetics();
        return true;
    }

    /** @see #addCosmetic(String) */
    public static void addCosmetics(@NotNull String @NotNull ... ids) {
        for (String id : ids) {
            addCosmetic(id, false);
        }
        saveCosmetics();
    }

    private static void saveCosmetics() {
        try {
            Files.write(COSMETICS_FILE.toPath(),
                    GSON.toJson(current.legacyCosmetics, SET_TYPE).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save cosmetics list!", e);
        }
    }

    /**
     * Downloads and merges from GitHub in the background.
     */
    public static void downloadCosmeticsList() {
        new Thread(() -> {
            try {
                String body;
                try (InputStream in = URI.create("https://github.com/prometheusreengineering/minecraft-essential/raw/refs/heads/main/src/main/resources/cosmetics.json")
                        .toURL().openStream()) {
                    body = readAll(in);
                }
                String[] cosmetics = GSON.<Set<String>>fromJson(body, SET_TYPE).toArray(new String[0]);
                addCosmetics(cosmetics);
                logger.info("Merged " + cosmetics.length + " cosmetics!");
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to merge new cosmetics!", e);
            }
        }).start();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
