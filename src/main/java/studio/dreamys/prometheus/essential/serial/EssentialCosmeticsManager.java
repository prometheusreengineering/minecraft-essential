package studio.dreamys.prometheus.essential.serial;

import gg.essential.cosmetics.model.Cosmetic;
import org.jetbrains.annotations.NotNull;
import studio.dreamys.prometheus.essential.util.GsonUtil;
import studio.dreamys.prometheus.essential.util.OS;
import studio.dreamys.prometheus.essential.util.PathUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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
     * Essential's specific folder. On unix-like systems this is outside .minecraft.
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
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        EssentialCosmeticsFileData.downloadCosmeticsList();
        logger.info("Loaded cosmetics!");
    }

    // Essential auto-generates UUIDs for vanilla's capes
    private static final @NotNull Predicate<@NotNull String> IS_VALID_COSMETIC_REGEX = // Dummy ID used by Essential's checkout system.
            Pattern.compile("^[A-Z0-9_]+$").asPredicate().and((s) -> !s.equalsIgnoreCase("ESSENTIAL_PURCHASE_CONFIRMATION"));

    // Called by MixinServerCosmeticsPopulatePacketHandler once for every cosmetic.
    public static void addCosmetic(@NotNull Cosmetic cosmetic) {
        String id = cosmetic.getId();
        if (!IS_VALID_COSMETIC_REGEX.test(id)) {
            logger.fine("Excluding cosmetic " + id);
            return;
        }
        logger.fine("Saving " + id + "!");
        EssentialCosmeticsFileData.addCosmetic(id);
        // Dump cosmetic
        Path dumpDir = Paths.get(DUMPS_PATH.toString(), cosmetic.getType());
        File dump = new File(dumpDir.toFile(), id + ".json");
        try {
            if (!Files.exists(dumpDir)) {
                Files.createDirectories(dumpDir);
            }
            GsonUtil.writePretty(dump.toPath(), cosmetic, cosmetic.getClass());
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to dump cosmetic " + id, e);
        }
    }

    // Called by MixinServerCosmeticsPopulatePacketHandler after the populate loop completes.
    public static void flushCosmetics() {
        // TODO: only flush if cosmetic list changed?
        EssentialCosmeticsFileData.saveCosmetics();
    }
}
