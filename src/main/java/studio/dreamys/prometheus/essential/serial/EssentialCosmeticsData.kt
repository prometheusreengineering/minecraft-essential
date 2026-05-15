package studio.dreamys.prometheus.essential.serial

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
internal value class EssentialCosmeticsData(
    internal val legacyCosmetics: MutableSet<String> = mutableSetOf()
) {
    /* This is a value class so it serializes to the Set's `["a", "b", "c"]` */
}
