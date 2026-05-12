package studio.dreamys.prometheus.serial

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
internal value class EssentialCosmeticsData(
    internal val legacyCosmetics: MutableSet<String> = mutableSetOf()
) {
    /* This is an @JvmInline class so it serializes to `["a", "b", "c"]` */
}