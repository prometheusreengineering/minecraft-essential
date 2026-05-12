package studio.dreamys.prometheus.serial

import kotlinx.serialization.json.Json
import studio.dreamys.prometheus.util.OS
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlin.io.path.*

object EssentialCosmeticsManager {
    private val logger: Logger = Logger.getLogger("Prometheus")
    private val cosmeticsData: EssentialCosmeticsData

    @JvmField
    val PROMETHEUS_FOLDER = Path("prometheus", "essential")
    private val PROMETHEUS_GLOBAL_FOLDER = when (OS.current) {
        OS.Windows -> PROMETHEUS_FOLDER // No global folder on Windows because of the lack of true symlinks
        OS.Linux -> Path(System.getenv("XDG_DATA_HOME"), "prometheus", "essential")
        OS.MacOS -> Path(System.getProperty("user.home"), "Library", "Application Support", "prometheus", "essential");
    }

    @JvmField
    val DUMPS_PATH = Path(PROMETHEUS_FOLDER.absolutePathString(), "dumps")

    @JvmField
    val COSMETICS_FILE = File(PROMETHEUS_FOLDER.toFile(), "cosmetics.json")

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
       thread {
           val body: String
           try {
               // CHANGE ME BEFORE MERGING
               val inputStream = URI("https://github.com/prometheusreengineering/minecraft-essential/raw/refs/heads/feat/legacy-list/src/main/resources/cosmetics.json").toURL().openStream()
               body = inputStream.reader().readText()
           } catch (_: Exception) {
               logger.warning("Failed to download new cosmetics!")
               return@thread
           }
           try {
               logger.info("Body text: \n$body")
               var count = 0
               Json.decodeFromString<EssentialCosmeticsData>(body).legacyCosmetics.forEach { id ->
                   if (addCosmetic(id, false)) count++
               }
               saveCosmetics()
               logger.info("Merged $count cosmetics!")
           } catch (e: Exception) {
               logger.warning("Failed to download new cosmetics!")
               e.printStackTrace()
           }
       }
    }

    @JvmStatic
    fun addCosmetic(id: String)
        = addCosmetic(id, true)
    private fun addCosmetic(id: String, save: Boolean): Boolean {
        if (id in cosmeticsData.legacyCosmetics) return false;
        cosmeticsData.legacyCosmetics += id
        if (save) saveCosmetics()
        return true;
    }

    @JvmStatic
    fun getLegacyCosmetics()
        = cosmeticsData.legacyCosmetics

    private fun saveCosmetics() {
        COSMETICS_FILE.writeText(Json.encodeToString(cosmeticsData))
    }
}