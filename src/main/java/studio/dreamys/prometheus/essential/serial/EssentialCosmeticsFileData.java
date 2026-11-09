package studio.dreamys.prometheus.essential.serial;

import gg.essential.lib.gson.Gson;
import gg.essential.lib.gson.JsonParseException;
import gg.essential.lib.gson.reflect.TypeToken;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import studio.dreamys.prometheus.essential.util.GsonUtil;

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
import java.util.concurrent.Callable;
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
    static final @NotNull URI DOWNLOAD_URL = URI.create(
            "https://raw.githubusercontent.com/prometheusreengineering/minecraft-essential/refs/heads/main/src/main/resources/cosmetics.json");
    private static final int DOWNLOAD_TIMEOUT_MS = 10_000;

    private final @NotNull Path file;
    private final @NotNull Callable<@Nullable InputStream> bundled;
    private final @NotNull URI downloadUrl;

    /** Guarded by itself. */
    private final @NotNull Set<@NotNull String> legacyCosmetics;
    private boolean isLegacyCosmeticsDirty;
    /** Set when the downloaded list added cosmetics that haven't been requested from Essential yet. */
    private volatile boolean hasUnrequestedCosmetics;
    private final Object saveLock = new Object();

    /** Loads {@code file}, seeding it from the bundled list the first time; tests pass their own paths. */
    EssentialCosmeticsFileData(@NotNull Path file, @NotNull Callable<@Nullable InputStream> bundled, @NotNull URI downloadUrl) {
        this.file = file;
        this.bundled = bundled;
        this.downloadUrl = downloadUrl;
        Set<String> loaded = null;
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                try (InputStream in = bundled.call()) {
                    if (in != null) Files.copy(in, file);
                }
            }
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            loaded = GSON.fromJson(json, SET_TYPE);
        } catch (JsonParseException e) {
            logger.log(Level.SEVERE, "cosmetics.json is corrupt, using the bundled list", e);
        } catch (Exception e) {
            // done to avoid crashing the game, cosmetics.json is only a cache of the bundled/downloaded lists
            logger.log(Level.SEVERE, "Failed to read cosmetics.json, using the bundled list", e);
        }
        boolean fallback = loaded == null;
        this.legacyCosmetics = fallback ? readBundled() : loaded;
        this.isLegacyCosmeticsDirty = fallback; // overwrite the broken file on the next save
    }

    /** The instance the game uses, created on first use (so merely loading this class touches no files). */
    private static final class Current {
        static final @NotNull EssentialCosmeticsFileData INSTANCE = new EssentialCosmeticsFileData(
                EssentialCosmeticsManager.PROMETHEUS_ESSENTIAL_FOLDER.resolve("cosmetics.json"),
                () -> EssentialCosmeticsFileData.class.getClassLoader().getResourceAsStream("cosmetics.json"),
                DOWNLOAD_URL);
    }

    static @NotNull EssentialCosmeticsFileData current() {
        return Current.INSTANCE;
    }

    /** Never throws, returns an empty set if the bundled list can't be read. */
    private @NotNull Set<String> readBundled() {
        try (InputStream in = bundled.call()) {
            if (in == null) throw new IOException("Bundled cosmetics.json not found!");
            Set<String> set = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), SET_TYPE);
            if (set != null) return set;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to read the bundled cosmetics list, starting with an empty list", e);
        }
        return new LinkedHashSet<>();
    }

    // called by MixinCosmeticsManager
    /** Returns whether there were unrequested cosmetics, and clears the flag. */
    public static boolean consumeUnrequested() {
        return current().consumeUnrequestedFlag();
    }

    boolean consumeUnrequestedFlag() {
        if (!hasUnrequestedCosmetics) return false;
        hasUnrequestedCosmetics = false;
        return true;
    }

    // called by MixinCosmeticsManager
    @Contract(value = "-> new", pure = true)
    public static @NotNull Set<@NotNull String> getCosmetics() {
        return current().cosmetics();
    }

    @NotNull Set<@NotNull String> cosmetics() {
        synchronized (legacyCosmetics) {
            return new LinkedHashSet<>(legacyCosmetics);
        }
    }

    /** Merges a cosmetic ID into the list (does not save the list to disk). */
    public static boolean addCosmetic(@NotNull String id) {
        return current().add(id);
    }

    boolean add(@NotNull String id) {
        boolean added;
        synchronized (legacyCosmetics) {
            added = legacyCosmetics.add(id);
            if (added) isLegacyCosmeticsDirty = true;
        }
        return added;
    }

    /** @see #addCosmetic(String) @return how many were new */
    public static int addCosmetics(@NotNull String @NotNull ...ids) {
        return current().addAll(ids);
    }

    int addAll(@NotNull String @NotNull ...ids) {
        int added = 0;
        for (String id : ids) if (add(id)) added++;
        return added;
    }

    /** Dumps the cosmetic list to disk. */
    public static void saveCosmetics() {
        current().save();
    }

    void save() {
        synchronized (saveLock) {
            Set<String> sorted;
            synchronized (legacyCosmetics) {
                if (!isLegacyCosmeticsDirty) return;
                sorted = new TreeSet<>(legacyCosmetics); // TreeSet is sorted
                isLegacyCosmeticsDirty = false;
            }
            try {
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                try {
                    Files.createDirectories(tmp.toAbsolutePath().getParent());
                    GsonUtil.writePretty(tmp, sorted, SET_TYPE);
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } catch (IOException e) {
                synchronized (legacyCosmetics) {
                    isLegacyCosmeticsDirty = true; // write failed; keep the change pending for a later retry
                }
                logger.log(Level.SEVERE, "Failed to save cosmetics list!", e);
            }
        }
    }

    /**
     * Downloads and merges from GitHub in the background.
     */
    public static void downloadCosmeticsList() {
        Thread thread = new Thread(() -> current().download(), "Prometheus - ECFD - DownloadCosmeticsList");
        thread.setDaemon(true);
        thread.start();
    }

    /** Downloads, merges and saves the list; never throws. @return how many were new, or -1 if it failed */
    int download() {
        try {
            URLConnection connection = downloadUrl.toURL().openConnection();
            connection.setConnectTimeout(DOWNLOAD_TIMEOUT_MS);
            connection.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
            String[] cosmetics;
            try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                cosmetics = GSON.fromJson(reader, String[].class);
            }
            if (cosmetics == null) throw new IOException("Downloaded cosmetics list is empty");
            int added = addAll(cosmetics);
            if (added > 0) hasUnrequestedCosmetics = true;
            save();
            logger.info("Merged " + cosmetics.length + " cosmetics (" + added + " new)!");
            return added;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to merge new cosmetics!", e);
            return -1;
        }
    }
}
