package studio.dreamys.prometheus.essential.serial

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import studio.dreamys.prometheus.essential.ext.File
import studio.dreamys.prometheus.essential.ext.consume
import studio.dreamys.prometheus.essential.serial.EssentialCosmeticsFileData.Companion.COSMETICS_FILE
import studio.dreamys.prometheus.essential.serial.EssentialCosmeticsFileData.Companion.addCosmetic
import studio.dreamys.prometheus.essential.serial.EssentialCosmeticsManager.PROMETHEUS_ESSENTIAL_FOLDER
import java.io.FileOutputStream
import java.net.URI
import java.util.logging.Logger
import kotlin.concurrent.thread

@JvmInline
@Serializable
value class EssentialCosmeticsFileData internal constructor(
    internal val legacyCosmetics: MutableSet<String> = mutableSetOf()
) {
    /* This is a value class so it serializes to the Set's `["a", "b", "c"]` */
    companion object {
        private val logger = Logger.getLogger("Prometheus - ECFD")
        private val COSMETICS_FILE = File(PROMETHEUS_ESSENTIAL_FOLDER, "cosmetics.json")
        private var current: EssentialCosmeticsFileData = run {
            if (!COSMETICS_FILE.exists()) {
                this::class.java.classLoader.getResourceAsStream("cosmetics.json")!!.copyTo(FileOutputStream(COSMETICS_FILE))
            }
            return@run Json.decodeFromString(COSMETICS_FILE.readText())
        }

        // called by MixinServerCosmeticsPopulatePacketHandler
        @JvmStatic
        fun getCosmetics() = current.legacyCosmetics

        /** Merges a cosmetic ID into the list, dumps it to file */
        fun addCosmetic(id: String)
                = addCosmetic(id, true)
        private fun addCosmetic(id: String, save: Boolean): Boolean {
            if (current.legacyCosmetics.contains(id)) return false
            current.legacyCosmetics += id
            if (save) saveCosmetics()
            return true
        }

        /** @see [addCosmetic] */
        fun addCosmetics(vararg ids: String) {
            for (id in ids)
                addCosmetic(id, false)
            saveCosmetics()
        }

        private fun saveCosmetics() {
            COSMETICS_FILE.writeText(Json.encodeToString(current))
        }

        /**
         * Downloads and merges from GitHub in the background.
         * @see COSMETICS_FILE
         */
        fun downloadCosmeticsList() {
            val t = thread {
                val body: String = URI("https://github.com/prometheusreengineering/minecraft-essential/raw/refs/heads/main/src/main/resources/cosmetics.json")
                    .toURL()
                    .openStream()
                    .consume()
                try {
                    val cosmetics = Json.decodeFromString<EssentialCosmeticsFileData>(body).legacyCosmetics.toTypedArray()
                    addCosmetics(*cosmetics)
                    logger.info("Merged ${cosmetics.size} cosmetics!")
                } catch (e: Exception) {
                    logger.warning("Failed to merge new cosmetics!")
                    e.printStackTrace()
                }
            }
        }
    }
}
