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
import java.net.URLConnection;
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
        Set<String> loaded = null;
        try {
            if (!COSMETICS_FILE.exists()) {
                Files.createDirectories(COSMETICS_FILE.getParentFile().toPath());
                try (InputStream in = openBundled()) {
                    if (in != null) Files.copy(in, COSMETICS_FILE.toPath());
                }
            }
            String json = new String(Files.readAllBytes(COSMETICS_FILE.toPath()), StandardCharsets.UTF_8);
            loaded = GSON.fromJson(json, SET_TYPE);
        } catch (IOException e) {
            // done to avoid crashing the game, cosmetics.json is only a cache of the bundled/downloaded lists
            logger.log(Level.SEVERE, "Failed to read cosmetics.json, using the bundled list", e);
        } catch (JsonParseException e) {
            logger.log(Level.SEVERE, "cosmetics.json is corrupt, using the bundled list", e);
        }
        boolean fallback = loaded == null;
        current = new EssentialCosmeticsFileData(fallback ? readBundled() : loaded);
        current.isLegacyCosmeticsDirty = fallback; // overwrite the broken file on the next save
    }

    private static InputStream openBundled() {
        return EssentialCosmeticsFileData.class.getClassLoader().getResourceAsStream("cosmetics.json");
    }

    /** Never throws, returns an empty set if the bundled list can't be read. */
    private static @NotNull Set<String> readBundled() {
        try (InputStream in = openBundled()) {
            if (in == null) throw new IOException("Bundled cosmetics.json not found!");
            Set<String> set = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), SET_TYPE);
            if (set != null) return set;
        } catch (IOException | JsonParseException e) {
            logger.log(Level.SEVERE, "Failed to read the bundled cosmetics list, starting with an empty list", e);
        }
        return new LinkedHashSet<>();
    }

    /** Set when the downloaded list added cosmetics that haven't been requested from Essential yet. */
    private static volatile boolean hasUnrequestedCosmetics;

    // called by MixinCosmeticsManager
    /** Returns whether there were unrequested cosmetics, and clears the flag. */
    public static boolean consumeUnrequested() {
        if (!hasUnrequestedCosmetics) return false;
        hasUnrequestedCosmetics = false;
        return true;
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

    /** @see #addCosmetic(String) @return how many were new */
    public static int addCosmetics(@NotNull String @NotNull ...ids) {
        int added = 0;
        for (String id : ids) if (addCosmetic(id)) added++;
        return added;
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
                    Files.createDirectories(tmp.getParent());
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

    private static final int DOWNLOAD_TIMEOUT_MS = 10_000;

    /**
     * Downloads and merges from GitHub in the background.
     */
    public static void downloadCosmeticsList() {
        Thread thread = new Thread(() -> {
            try {
                URLConnection connection = URI.create("https://raw.githubusercontent.com/prometheusreengineering/minecraft-essential/refs/heads/main/src/main/resources/cosmetics.json")
                        .toURL().openConnection();
                connection.setConnectTimeout(DOWNLOAD_TIMEOUT_MS);
                connection.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
                String[] cosmetics;
                try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                    cosmetics = GSON.fromJson(reader, String[].class);
                }
                if (cosmetics == null) throw new IOException("Downloaded cosmetics list is empty");
                int added = addCosmetics(cosmetics);
                if (added > 0) hasUnrequestedCosmetics = true;
                saveCosmetics();
                logger.info("Merged " + cosmetics.length + " cosmetics (" + added + " new)!");
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to merge new cosmetics!", e);
            }
        }, "Prometheus - ECFD - DownloadCosmeticsList");
        thread.setDaemon(true);
        thread.start();
    }
}
