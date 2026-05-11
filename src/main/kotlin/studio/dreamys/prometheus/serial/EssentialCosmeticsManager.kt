package studio.dreamys.prometheus.serial

import kotlinx.serialization.json.Json
import studio.dreamys.prometheus.util.OS
import java.io.File
import java.io.FileOutputStream
import java.util.logging.Logger
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists

object EssentialCosmeticsManager {
    private val logger: Logger = Logger.getLogger("Prometheus")
    private val cosmeticsData: EssentialCosmeticsData

    @JvmField
    val PROMETHEUS_FOLDER = Path("prometheus")
    private val PROMETHEUS_GLOBAL_FOLDER = when (OS.current) {
        OS.Windows -> PROMETHEUS_FOLDER // No global folder on Windows because of the lack of true symlinks
        OS.Linux -> Path(System.getenv("XDG_DATA_HOME"), "prometheus")
        OS.MacOS -> Path(System.getProperty("user.home"), "Library", "Application Support", "prometheus");
    }

    @JvmField
    val DUMPS_PATH = Path(PROMETHEUS_FOLDER.absolutePathString(), "dumps/essential")

    @JvmField
    val COSMETICS_FILE = File(PROMETHEUS_FOLDER.toFile(), "essential.json")

    private fun loadCosmeticsFile() {
        if (!COSMETICS_FILE.exists()) {
            val bundledCosmeticsFile = this::class.java.classLoader.getResourceAsStream("cosmetics.json") ?: return
            bundledCosmeticsFile.copyTo(FileOutputStream(COSMETICS_FILE))
        }
    }

    private fun tryCreateSymlinks() {
        if (PROMETHEUS_FOLDER.exists()) { // TODO: Migrate folder?
            return
        } else if (OS.isOnWindows()) { // Windows doesn't support Symlinks
            logger.info("Windows machine, skipping symlinks!")
            PROMETHEUS_FOLDER.createDirectories()
            return
        }
        if (!PROMETHEUS_GLOBAL_FOLDER.exists()) {
            PROMETHEUS_GLOBAL_FOLDER.createDirectories()
        }
        PROMETHEUS_FOLDER.createSymbolicLinkPointingTo(PROMETHEUS_GLOBAL_FOLDER)
    }

    init {
        tryCreateSymlinks()
        if (!DUMPS_PATH.exists()) {
            DUMPS_PATH.createDirectories()
        }
        loadCosmeticsFile()
        cosmeticsData = Json.decodeFromString(COSMETICS_FILE.readText())
    }

    @JvmStatic
    fun downloadCosmeticsList() {
        // TODO: Fetch from GitHub
        // no TODO() because of the `init {}` block.
    }

    @JvmStatic
    fun addCosmetic(id: String): Boolean {
        if (id in cosmeticsData.legacyCosmetics) return false;
        cosmeticsData.legacyCosmetics += id
        saveCosmetics()
        return true;
    }

    @JvmStatic
    fun getLegacyCosmetics()
        = cosmeticsData.legacyCosmetics

    private fun saveCosmetics() {
        COSMETICS_FILE.writeText(Json.encodeToString(cosmeticsData))
    }
}