package studio.dreamys.prometheus.essential.serial;

import gg.essential.cosmetics.model.Cosmetic;
import org.jetbrains.annotations.NotNull;
import studio.dreamys.prometheus.essential.util.GsonUtil;
import studio.dreamys.prometheus.essential.util.OS;
import studio.dreamys.prometheus.essential.util.PathUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class EssentialCosmeticsManager {
    private EssentialCosmeticsManager() {}

    private static final @NotNull Logger logger = Logger.getLogger("Prometheus - ECM");

    /**
     * Instance-specific folder for all prometheus patches. This sits inside .minecraft
     */
    private static final @NotNull Path PROMETHEUS_FOLDER = Paths.get("prometheus");
    /**
     * Essential's specific folder. This is global (not per instance): %APPDATA%/.minecraft on Windows, outside .minecraft elsewhere.
     * Use this.
     */
    public static final @NotNull Path PROMETHEUS_ESSENTIAL_FOLDER = OS.getConfigFolder().resolve("essential").normalize();

    public static final @NotNull Path DUMPS_PATH = PROMETHEUS_ESSENTIAL_FOLDER.resolve("dumps");

    private static void tryCreateSymlinks() throws IOException {
        // If there is a link there
        if (Files.exists(PROMETHEUS_FOLDER, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(PROMETHEUS_FOLDER) && !Files.exists(PROMETHEUS_FOLDER)) {
                Files.delete(PROMETHEUS_FOLDER); // We have a broken link, delete and replace
            } else {
                return;
            }
        }
        if (OS.isOnWindows()) { // Windows doesn't support symlinks
            logger.info("Windows machine, skipping symlinks!");
            Files.createDirectories(PROMETHEUS_ESSENTIAL_FOLDER); // this is the local folder
            return;
        }
        if (!Files.exists(PROMETHEUS_ESSENTIAL_FOLDER)) {
            Files.createDirectories(PROMETHEUS_ESSENTIAL_FOLDER);
        }
        Files.createSymbolicLink(PROMETHEUS_FOLDER, PROMETHEUS_ESSENTIAL_FOLDER.getParent());
    }

    /**
     * Windows used to keep everything in the instance's local folder (and so could a local folder that existed before
     * the symlink). Merge it into the global one without overwriting, then rename it so this only runs once.
     */
    private static void migrateLocalEssentialFolder() throws IOException {
        Path localFolder = PROMETHEUS_FOLDER.resolve("essential");
        if (Files.isSymbolicLink(PROMETHEUS_FOLDER) || !Files.isDirectory(localFolder, LinkOption.NOFOLLOW_LINKS)) return;
        Files.createDirectories(PROMETHEUS_ESSENTIAL_FOLDER);
        if (Files.isSameFile(localFolder, PROMETHEUS_ESSENTIAL_FOLDER)) return;

        PathUtil.copyMissingRecursively(localFolder, PROMETHEUS_ESSENTIAL_FOLDER);
        Path migrated = PROMETHEUS_FOLDER.resolve("essential.migrated");
        if (!Files.exists(migrated)) {
            Files.move(localFolder, migrated);
        }
        logger.info("Migrated " + localFolder.toAbsolutePath() + " to " + PROMETHEUS_ESSENTIAL_FOLDER);
    }

    // called by MixinEssential
    public static void setupFolderStructure() {
        try {
            tryCreateSymlinks();
            if (!Files.exists(DUMPS_PATH)) {
                Files.createDirectories(DUMPS_PATH);
                // migration
                Path oldDumpsFolder = PROMETHEUS_FOLDER.resolve("dumps").resolve("essential").normalize();
                if (Files.exists(oldDumpsFolder)) {
                    List<Path> folders;
                    try (Stream<Path> entries = Files.list(oldDumpsFolder)) {
                        folders = entries.collect(Collectors.toList());
                    }
                    for (Path folder : folders) {
                        PathUtil.copyRecursively(folder, DUMPS_PATH.resolve(folder.getFileName()));
                    }
                    PathUtil.deleteRecursively(oldDumpsFolder);
                    if (PathUtil.isEmptyOrNull(oldDumpsFolder.getParent().toFile())) {
                        Files.delete(oldDumpsFolder.getParent());
                    }
                    // If the *local* folder is empty we can delete it and point it to the global one
                    if (PathUtil.isEmptyOrNull(PROMETHEUS_FOLDER.toFile())) {
                        Files.delete(PROMETHEUS_FOLDER);
                        tryCreateSymlinks();
                    }
                }
            }
            migrateLocalEssentialFolder();
        } catch (IOException e) {
            // done to avoid crashing the game, dumps and the cosmetics list just won't be saved
            logger.log(Level.SEVERE, "Failed to set up the prometheus folder!", e);
        }
        EssentialCosmeticsFileData.downloadCosmeticsList();
        logger.info("Loaded cosmetics!");
    }

    // Essential auto-generates UUIDs for vanilla's capes
    private static final @NotNull Predicate<@NotNull String> IS_VALID_COSMETIC_REGEX = // Dummy ID used by Essential's checkout system.
            Pattern.compile("^[A-Z0-9_]+$").asPredicate().and((s) -> !s.equalsIgnoreCase("ESSENTIAL_PURCHASE_CONFIRMATION"));

    // The type comes from the server and becomes a folder name, so keep it to plain names (no "..", slashes, etc.)
    private static final @NotNull Predicate<@NotNull String> IS_VALID_TYPE_REGEX = Pattern.compile("^[A-Z0-9_]+$").asPredicate();

    /**
     * Disk writes happen here instead of on the caller's (main) thread, populate packets carry 1000+ cosmetics.
     * Single thread so writes stay in order.
     */
    private static final @NotNull ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Prometheus - ECM - IO");
        thread.setDaemon(true);
        return thread;
    });

    /** Runs a disk write on the shared IO thread, in submission order. */
    static void runIo(@NotNull Runnable task) {
        IO_EXECUTOR.execute(task);
    }

    // Called by MixinServerCosmeticsPopulatePacketHandler once for every cosmetic (on the main thread).
    public static void addCosmetic(@NotNull Cosmetic cosmetic) {
        String id = cosmetic.getId();
        if (!IS_VALID_COSMETIC_REGEX.test(id)) {
            logger.fine("Excluding cosmetic " + id);
            return;
        }
        logger.fine("Saving " + id + "!");
        EssentialCosmeticsFileData.addCosmetic(id);
        String type = cosmetic.getType();
        if (type == null || !IS_VALID_TYPE_REGEX.test(type)) {
            logger.fine("Not dumping cosmetic " + id + " with unexpected type " + type);
            return;
        }
        IO_EXECUTOR.execute(() -> dumpCosmetic(cosmetic, DUMPS_PATH.resolve(type).resolve(id + ".json")));
    }

    private static void dumpCosmetic(@NotNull Cosmetic cosmetic, @NotNull Path dump) {
        try {
            byte[] json = GsonUtil.toPrettyString(cosmetic, cosmetic.getClass()).getBytes(StandardCharsets.UTF_8);
            // every (re)connect sends every cosmetic again, skip rewriting the ones that didn't change
            if (Files.exists(dump) && Arrays.equals(Files.readAllBytes(dump), json)) return;
            Files.createDirectories(dump.getParent());
            Files.write(dump, json);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to dump cosmetic " + cosmetic.getId(), e);
        }
    }

    // Called by MixinServerCosmeticsPopulatePacketHandler after the populate loop completes.
    public static void flushCosmetics() {
        IO_EXECUTOR.execute(EssentialCosmeticsFileData::saveCosmetics); // no-op if the list didn't change
    }
}
