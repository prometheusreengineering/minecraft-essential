package studio.dreamys.prometheus.essential.serial

import gg.essential.cosmetics.model.Cosmetic
import gg.essential.lib.gson.Gson
import gg.essential.lib.gson.GsonBuilder
import studio.dreamys.prometheus.essential.ext.*
import studio.dreamys.prometheus.essential.serial.EssentialCosmeticsFileData.Companion.addCosmetic
import studio.dreamys.prometheus.essential.serial.EssentialCosmeticsFileData.Companion.downloadCosmeticsList
import studio.dreamys.prometheus.essential.util.OS
import java.util.logging.Logger
import kotlin.io.path.*

object EssentialCosmeticsManager {
    private val logger: Logger = Logger.getLogger("Prometheus - ECM")
    // Used for Essential's serialization
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** Global folder for all Prometheus patches */
    private val PROMETHEUS_FOLDER = Path("prometheus") // global folder for all patched mods/clients
    /** Essential's specific folder. Use this. */
    val PROMETHEUS_ESSENTIAL_FOLDER = OS.current
        .configFolder
        .resolve("prometheus", "essential")
        .normalize()

    val DUMPS_PATH = Path(PROMETHEUS_ESSENTIAL_FOLDER, "dumps")

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

    // called by MixinEssential
    @JvmStatic
    fun setupFolderStructure() {
        tryCreateSymlinks()
        if (!DUMPS_PATH.exists()) {
            DUMPS_PATH.createDirectories()
            // migration
            val oldDumpsFolder = PROMETHEUS_FOLDER.resolve("dumps", "essential").normalize()
            if (oldDumpsFolder.exists()) {
                for (folder in oldDumpsFolder.listDirectoryEntries()) {
                    folder.copyRecursively(DUMPS_PATH.resolve(folder.fileName), overwrite = true)
                }
                oldDumpsFolder.deleteRecursively()
                if (oldDumpsFolder.parent!!.toFile().listFiles()?.isEmpty() ?: true) {
                    oldDumpsFolder.parent.deleteExisting()
                }
                // If the *local* folder is empty we can delete it and point it to the global one
                if (PROMETHEUS_FOLDER.toFile().listFiles()?.isEmpty() ?: true) {
                    PROMETHEUS_FOLDER.deleteExisting()
                    tryCreateSymlinks()
                }
            }
        }
        downloadCosmeticsList()
        logger.info("Loaded cosmetics!")
    }

    // Called by MixinServerCosmeticsPopulatePacketHandler
    @JvmStatic
    fun addCosmetic(cosmetic: Cosmetic) {
        val id = cosmetic.id
        logger.fine("Saving $id!")
        addCosmetic(id)
        // Dump cosmetic
        File(DUMPS_PATH, cosmetic.type, "$id.json")
            .writeText(gson.toJson(cosmetic))
    }
}
