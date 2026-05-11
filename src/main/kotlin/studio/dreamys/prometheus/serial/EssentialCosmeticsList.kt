package studio.dreamys.prometheus.serial

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.SortedSet

@Serializable
data object EssentialCosmeticsList {
    @SerialName("legacy")
    @JvmField
    var legacyCosmetics: SortedSet<String> = sortedSetOf()

    @JvmStatic
    fun load() {
        val new = Json.decodeFromString<EssentialCosmeticsList>(EssentialCosmeticFileManager.COSMETICS_FILE.readText())
        for (cosmetic in new.legacyCosmetics) {
            legacyCosmetics += cosmetic
        }
    }
    @JvmStatic
    fun save() {
        val contents = Json.encodeToString(this)
        EssentialCosmeticFileManager.COSMETICS_FILE.writeText(contents)
    }
    @JvmStatic
    fun addCosmetic(id: String): Boolean {
        TODO("implement")
        save()
    }
}