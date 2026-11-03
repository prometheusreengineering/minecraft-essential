package studio.dreamys.prometheus.essential.mixin.gg.essential;

import gg.essential.Essential;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.dreamys.prometheus.essential.serial.EssentialCosmeticsManager;
import studio.dreamys.prometheus.essential.verity.VerityChecks;

@Mixin(value = Essential.class, remap = false)
public class MixinEssential {
    @Inject(method = "init", at = @At("HEAD"))
    void onInit(CallbackInfo ci) {
        EssentialCosmeticsManager.setupFolderStructure();
    }

    // Self-checks for the Verity CI matrix; a no-op outside Verity.
    @Inject(method = "init", at = @At("RETURN"))
    void onInitReturn(CallbackInfo ci) {
        VerityChecks.afterEssentialInit();
    }
}
