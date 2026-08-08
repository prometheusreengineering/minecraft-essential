package studio.dreamys.prometheus.essential.serial;

import gg.essential.lib.gson.Gson;
import gg.essential.lib.gson.JsonParseException;
import gg.essential.lib.gson.reflect.TypeToken;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import studio.dreamys.prometheus.essential.util.GsonUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Backing store for the legacy cosmetics list. Serializes as the bare Set's
 * {@code ["a", "b", "c"]} array via Gson.
 */
public class EssentialCosmeticsFileData {
    private static final @NotNull Logger logger = Logger.getLogger("Prometheus - ECFD");
    private static final Gson GSON = new Gson();
    private static final @NotNull Type SET_TYPE = new TypeToken<LinkedHashSet<String>>() {}.getType();
    private static final File COSMETICS_FILE =
            new File(EssentialCosmeticsManager.PROMETHEUS_ESSENTIAL_FOLDER.toFile(), "cosmetics.json");

    /** Guarded by itself. */
    private final @NotNull Set<@NotNull String> legacyCosmetics;
    private boolean isLegacyCosmeticsDirty;


    private EssentialCosmeticsFileData(@NotNull Set<String> legacyCosmetics) {
        this.legacyCosmetics = legacyCosmetics;
    }

    private static final @NotNull EssentialCosmeticsFileData current;

    static {
        Set<String> loaded = new LinkedHashSet<>();
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
            if (set != null) loaded = set;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (JsonParseException e) {
            // done to avoid crashing future launches
            logger.log(Level.SEVERE, "cosmetics.json is corrupt, starting with an empty list", e);
        }
        current = new EssentialCosmeticsFileData(loaded);
    }

    // called by MixinCosmeticsManager
    @Contract(value = "-> new", pure = true)
    public static @NotNull Set<@NotNull String> getCosmetics() {
        synchronized (current.legacyCosmetics) {
            return new LinkedHashSet<>(current.legacyCosmetics);
        }
    }

    /** Merges a cosmetic ID into the list (does not save the list to disk). */
    public static boolean addCosmetic(@NotNull String id) {
        boolean added;
        synchronized (current.legacyCosmetics) {
            added = current.legacyCosmetics.add(id);
            if (added) current.isLegacyCosmeticsDirty = true;
        }
        return added;
    }

    /** @see #addCosmetic(String) */
    public static void addCosmetics(@NotNull String @NotNull ...ids) {
        for (String id : ids) addCosmetic(id);
    }


    private static final Object SAVE_COSMETICS_WRITE_LOCK = new Object();
    /** Dumps the cosmetic list to disk. */
    public static void saveCosmetics() {
        synchronized (SAVE_COSMETICS_WRITE_LOCK) {
            Set<String> sorted;
            synchronized (current.legacyCosmetics) {
                if (!current.isLegacyCosmeticsDirty) return;
                sorted = new TreeSet<>(current.legacyCosmetics); // TreeSet is sorted
                current.isLegacyCosmeticsDirty = false;
            }
            try {
                Path tmp = COSMETICS_FILE.toPath().resolveSibling(COSMETICS_FILE.getName() + ".tmp");
                try {
                    GsonUtil.writePretty(tmp, sorted, SET_TYPE);
                    Files.move(tmp, COSMETICS_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } catch (IOException e) {
                synchronized (current.legacyCosmetics) {
                    current.isLegacyCosmeticsDirty = true; // write failed; keep the change pending for a later retry
                }
                logger.log(Level.SEVERE, "Failed to save cosmetics list!", e);
            }
        }
    }

    /**
     * Downloads and merges from GitHub in the background.
     */
    public static void downloadCosmeticsList() {
        Thread thread = new Thread(() -> {
            try (Reader reader = new InputStreamReader(
                    URI.create("https://github.com/prometheusreengineering/minecraft-essential/raw/refs/heads/main/src/main/resources/cosmetics.json")
                            .toURL().openStream(), StandardCharsets.UTF_8)) {
                String[] cosmetics = GsonUtil.toStringArray(reader);
                addCosmetics(cosmetics);
                saveCosmetics();
                logger.info("Merged " + cosmetics.length + " cosmetics!");
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to merge new cosmetics!", e);
            }
        }, "Prometheus - ECFD - DownloadCosmeticsList");
        thread.setDaemon(true);
        thread.start();
    }
}
