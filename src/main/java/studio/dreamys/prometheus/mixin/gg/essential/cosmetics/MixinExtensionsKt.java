package studio.dreamys.prometheus.mixin.gg.essential.cosmetics;

import gg.essential.cosmetics.ExtensionsKt;
import gg.essential.gui.elementa.state.v2.ObservedInstant;
import gg.essential.network.cosmetics.Cosmetic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(value = ExtensionsKt.class, remap = false)
public class MixinExtensionsKt {
    @SuppressWarnings({"OverwriteAuthorRequired", "FinalStaticMethod"})
    @Overwrite
    public static final boolean isAvailable(Cosmetic cosmetic, ObservedInstant now) {
        return true;
    }
}
