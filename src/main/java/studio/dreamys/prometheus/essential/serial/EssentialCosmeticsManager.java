package studio.dreamys.prometheus.essential.serial;

import gg.essential.cosmetics.model.Cosmetic;
import gg.essential.lib.gson.Gson;
import gg.essential.lib.gson.GsonBuilder;
import org.jetbrains.annotations.NotNull;
import studio.dreamys.prometheus.essential.util.OS;
import studio.dreamys.prometheus.essential.util.PathUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class EssentialCosmeticsManager {
    private EssentialCosmeticsManager() {}

    private static final Logger logger = Logger.getLogger("Prometheus - ECM");
    // Used for Essential's serialization
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** Instance-specific folder for all prometheus patches. This sits inside .minecraft */
    private static final Path PROMETHEUS_FOLDER = Paths.get("prometheus");
    /** Essential's specific folder. On unix-like systems this is outside .minecraft.
     * Use this. */
    public static final Path PROMETHEUS_ESSENTIAL_FOLDER = OS.getConfigFolder()
            .resolve("essential")
            .normalize();

    public static final Path DUMPS_PATH = PROMETHEUS_ESSENTIAL_FOLDER.resolve("dumps");

    private static void tryCreateSymlinks() throws IOException {
        if (Files.exists(PROMETHEUS_FOLDER)) {
            return;
        } else if (OS.isOnWindows()) { // Windows doesn't support symlinks
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

    // Called by MixinServerCosmeticsPopulatePacketHandler
    public static void addCosmetic(@NotNull Cosmetic cosmetic) {
        String id = cosmetic.getId();
        logger.fine("Saving " + id + "!");
        EssentialCosmeticsFileData.addCosmetic(id);
        // Dump cosmetic
        File dump = new File(new File(DUMPS_PATH.toFile(), cosmetic.getType()), id + ".json");
        try {
            Files.createDirectories(dump.toPath().getParent());
            Files.write(dump.toPath(), gson.toJson(cosmetic).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to dump cosmetic " + id, e);
        }
    }
}
