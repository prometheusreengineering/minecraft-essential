package studio.dreamys.prometheus.serial

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class EssentialCosmeticsData(
    @SerialName("legacy")
    internal var legacyCosmetics: Set<String> = mutableSetOf()
) {
}