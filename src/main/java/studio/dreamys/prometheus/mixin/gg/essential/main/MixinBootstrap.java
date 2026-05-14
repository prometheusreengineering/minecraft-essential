package studio.dreamys.prometheus.mixin.gg.essential.main;

import gg.essential.main.Bootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.dreamys.prometheus.serial.EssentialCosmeticsManager;

@Mixin(value = Bootstrap.class, remap = false)
public class MixinBootstrap {
    @Inject(method = "initialize", at = @At("RETURN"))
    private static void onInitialize(CallbackInfo ci) {
        EssentialCosmeticsManager.downloadCosmeticsList();
    }
}
