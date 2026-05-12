package studio.dreamys.prometheus.serial

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
internal value class EssentialCosmeticsData(
    @SerialName("legacy")
    internal val legacyCosmetics: MutableSet<String> = mutableSetOf()
) {
}