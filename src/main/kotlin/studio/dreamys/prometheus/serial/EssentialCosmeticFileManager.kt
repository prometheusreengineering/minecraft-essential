package studio.dreamys.prometheus.serial

import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object EssentialCosmeticFileManager {
    @JvmField
    val DUMPS_PATH = Path("prometheus/dumps/essential")
    @JvmField
    val COSMETICS_FILE = File("prometheus/essential.json")

    private fun loadCosmeticsFile() {
        if (!COSMETICS_FILE.exists()) {
            val bundledCosmeticsFile = this::class.java.classLoader.getResourceAsStream("cosmetics.json") ?: return
            bundledCosmeticsFile.copyTo(FileOutputStream(COSMETICS_FILE))
        }
    }

    init {
        if (!DUMPS_PATH.exists()) {
            DUMPS_PATH.createDirectories()
        }
        loadCosmeticsFile()
        EssentialCosmeticsList.load()
    }
}