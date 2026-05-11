package studio.dreamys.prometheus.mixin.gg.essential.network.connectionmanager.cosmetics;

import gg.essential.connectionmanager.common.packet.cosmetic.ClientCosmeticRequestPacket;
import gg.essential.event.client.ClientTickEvent;
import gg.essential.network.connectionmanager.ConnectionManager;
import gg.essential.network.connectionmanager.cosmetics.CosmeticsManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.dreamys.prometheus.serial.EssentialCosmeticsManager;

@Mixin(value = CosmeticsManager.class, remap = false)
public abstract class MixinCosmeticsManager {

    @Shadow
    public abstract void unlockAllCosmetics();

    @Shadow
    @Final
    private ConnectionManager connectionManager;

    @Unique
    private boolean requested;

    @Inject(method = "tick", at = @At("RETURN"))
    public void onTick(ClientTickEvent tickEvent, CallbackInfo ci) {
        unlockAllCosmetics();

        if (!requested) {
            requested = true;
            connectionManager.send(new ClientCosmeticRequestPacket(EssentialCosmeticsManager.getLegacyCosmetics(), null));
        }
    }
}
