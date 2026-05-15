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

    /** Global folder for all Prometheus patches */
    private val PROMETHEUS_FOLDER = Path("prometheus") // global folder for all patched mods/clients
    /** Essential's specific folder. Use this. */
    @JvmField
    val PROMETHEUS_ESSENTIAL_FOLDER = when (OS.current) {
        OS.Windows -> Path(PROMETHEUS_FOLDER.absolutePathString(), "essential") // No global folder on Windows because of the lack of true symlinks
        OS.Linux -> Path(System.getenv("XDG_DATA_HOME") ?:
            Path(System.getProperty("user.home"), ".local", "share").absolutePathString(),
            "prometheus", "essential")
        OS.MacOS -> Path(System.getProperty("user.home"), "Library", "Application Support", "prometheus", "essential")
    }

    @JvmField
    val DUMPS_PATH = Path(PROMETHEUS_ESSENTIAL_FOLDER.absolutePathString(), "dumps")

    @JvmField
    val COSMETICS_FILE = File(PROMETHEUS_ESSENTIAL_FOLDER.toFile(), "cosmetics.json")

    private fun tryCreateSymlinks() {
        if (PROMETHEUS_FOLDER.exists()) {
            return
        } else if (OS.isOnWindows()) { // Windows doesn't support symlinks
            logger.info("Windows machine, skipping symlinks!")
            PROMETHEUS_ESSENTIAL_FOLDER.createDirectories() // this is the local folder
            return
        }
        if (!PROMETHEUS_ESSENTIAL_FOLDER.exists()) {
            PROMETHEUS_ESSENTIAL_FOLDER.createDirectories()
        }
        PROMETHEUS_FOLDER.createSymbolicLinkPointingTo(PROMETHEUS_ESSENTIAL_FOLDER.parent)
    }

    init {
        tryCreateSymlinks()
        if (!DUMPS_PATH.exists()) {
            DUMPS_PATH.createDirectories()
            // migration
            val oldDumpsFolder = PROMETHEUS_FOLDER.resolve("dumps").resolve("essential").normalize()
            if (oldDumpsFolder.exists()) {
                oldDumpsFolder.listDirectoryEntries().forEach { file ->
                    file.toFile().copyRecursively(DUMPS_PATH.resolve(file.fileName.toString()).toFile(), overwrite = true)
                }
                oldDumpsFolder.toFile().deleteRecursively()
                if (oldDumpsFolder.parent!!.toFile().listFiles()?.isEmpty() ?: true) {
                    oldDumpsFolder.parent.deleteExisting()
                }
                // If the *local* folder is empty now we can delete it and point it to the global one
                if (PROMETHEUS_FOLDER.toFile().listFiles()?.isEmpty() ?: true) {
                    PROMETHEUS_FOLDER.deleteExisting()
                    tryCreateSymlinks()
                }
            }
        }
        if (!COSMETICS_FILE.exists()) {
            this::class.java.classLoader.getResourceAsStream("cosmetics.json")?.copyTo(FileOutputStream(COSMETICS_FILE))
        }
        cosmeticsData = Json.decodeFromString(COSMETICS_FILE.readText())
        logger.info("Loaded cosmetics!")
    }

    @JvmStatic
    fun downloadCosmeticsList() {
       thread {
           val body: String
           try {
               val inputStream = URI("https://github.com/prometheusreengineering/minecraft-essential/raw/refs/heads/main/src/main/resources/cosmetics.json").toURL().openStream()
               body = inputStream.reader().readText()
           } catch (_: Exception) {
               logger.warning("Failed to download new cosmetics!")
               return@thread
           }
           try {
               var count = 0
               Json.decodeFromString<EssentialCosmeticsData>(body).legacyCosmetics.forEach { id ->
                   if (addCosmetic(id, false)) count++
               }
               saveCosmetics()
               logger.info("Merged $count cosmetics!")
           } catch (e: Exception) {
               logger.warning("Failed to merge new cosmetics!")
               e.printStackTrace()
           }
       }
    }

    @JvmStatic
    fun addCosmetic(id: String)
        = addCosmetic(id, true)
    private fun addCosmetic(id: String, save: Boolean): Boolean {
        if (id in cosmeticsData.legacyCosmetics) return false
        cosmeticsData.legacyCosmetics += id
        if (save) saveCosmetics()
        return true
    }

    @JvmStatic
    val legacyCosmetics
        get() = cosmeticsData.legacyCosmetics

    private fun saveCosmetics() {
        COSMETICS_FILE.writeText(Json.encodeToString(cosmeticsData))
    }
}
