package studio.dreamys.prometheus.essential.mixin.gg.essential.network.connectionmanager.cosmetics;

import gg.essential.mod.cosmetics.CosmeticSlot;
import gg.essential.mod.cosmetics.settings.CosmeticSetting;
import gg.essential.network.connectionmanager.cosmetics.OutfitManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.dreamys.prometheus.essential.serial.EssentialOutfitOverrides;

import java.util.List;

@Mixin(value = OutfitManager.class, remap = false)
public class MixinOutfitManager {
    // Remember every slot change, the server silently drops legacy/expired cosmetics from outfits
    @Inject(method = "updateEquippedCosmetic", at = @At("HEAD"))
    private void onUpdateEquippedCosmetic(String outfitId, CosmeticSlot slot, String cosmeticId, CallbackInfo ci) {
        EssentialOutfitOverrides.setSlot(outfitId, slot.getId(), cosmeticId);
    }

    // Same for their settings (e.g. variants)
    @Inject(method = "updateOutfitCosmeticSettings", at = @At("HEAD"))
    private void onUpdateOutfitCosmeticSettings(String outfitId, String cosmeticId, List<? extends CosmeticSetting> settings, CallbackInfo ci) {
        EssentialOutfitOverrides.setSettings(outfitId, cosmeticId, settings);
    }

    @Inject(method = "deleteOutfit", at = @At("HEAD"))
    private void onDeleteOutfit(String id, CallbackInfo ci) {
        EssentialOutfitOverrides.removeOutfit(id);
    }
}
