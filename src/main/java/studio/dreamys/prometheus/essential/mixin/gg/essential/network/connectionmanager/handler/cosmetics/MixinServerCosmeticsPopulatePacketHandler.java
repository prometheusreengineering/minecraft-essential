package studio.dreamys.prometheus.essential.mixin.gg.essential.network.connectionmanager.handler.cosmetics;

import gg.essential.connectionmanager.common.packet.cosmetic.ServerCosmeticsPopulatePacket;
import gg.essential.cosmetics.model.Cosmetic;
import gg.essential.network.connectionmanager.handler.cosmetics.ServerCosmeticsPopulatePacketHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.dreamys.prometheus.essential.serial.EssentialCosmeticsManager;

@Mixin(value = ServerCosmeticsPopulatePacketHandler.class, remap = false)
public class MixinServerCosmeticsPopulatePacketHandler {
    // Run for every cosmetic
    // (reading the call's argument instead of capturing locals, which would silently skip if the locals ever differ)
    @ModifyArg(method = "handle(Lgg/essential/connectionmanager/common/packet/cosmetic/ServerCosmeticsPopulatePacket;)V", at = @At(value = "INVOKE", target = "Lgg/essential/network/connectionmanager/cosmetics/InfraCosmeticsData;addCosmetic(Lgg/essential/cosmetics/model/Cosmetic;)V"))
    public Cosmetic handleAddCosmetic(Cosmetic cosmetic) {
        EssentialCosmeticsManager.addCosmetic(cosmetic);
        return cosmetic;
    }

    // Run once after all cosmetics (above) are done
    @Inject(method = "handle(Lgg/essential/connectionmanager/common/packet/cosmetic/ServerCosmeticsPopulatePacket;)V", at = @At("RETURN"))
    public void handleReturn(ServerCosmeticsPopulatePacket packet, CallbackInfo ci) {
        EssentialCosmeticsManager.flushCosmetics();
    }
}
