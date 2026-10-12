package studio.dreamys.prometheus.essential.mixin.gg.essential.network.cosmetics;

import gg.essential.mod.cosmetics.CosmeticOutfit;
import gg.essential.network.cosmetics.ConversionsKt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import studio.dreamys.prometheus.essential.serial.EssentialOutfitOverrides;

import java.util.List;

@Mixin(value = ConversionsKt.class, remap = false)
public class MixinConversionsKt {
    // Only used by OutfitManager's ServerCosmeticOutfitPopulatePacket handler, so this is where server outfits come in.
    // (hooked here instead of the handler since that's a lambda)
    @SuppressWarnings("NameDoesntMatchTargetClass")
    @Inject(method = "outfitsToMod", at = @At("RETURN"), cancellable = true)
    private static void onOutfitsToMod(List<?> outfits, CallbackInfoReturnable<List<CosmeticOutfit>> cir) {
        cir.setReturnValue(EssentialOutfitOverrides.apply(cir.getReturnValue()));
    }
}
