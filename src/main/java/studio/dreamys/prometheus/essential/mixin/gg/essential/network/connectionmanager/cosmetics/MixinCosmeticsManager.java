package studio.dreamys.prometheus.essential.mixin.gg.essential.network.connectionmanager.cosmetics;

import com.sparkuniverse.toolbox.util.DateTime;
import gg.essential.cosmetics.model.CosmeticUnlockData;
import gg.essential.event.client.ClientTickEvent;
import gg.essential.network.connectionmanager.ConnectionManager;
import gg.essential.network.connectionmanager.cosmetics.CosmeticsManager;
import gg.essential.network.connectionmanager.cosmetics.InfraCosmeticsData;
import gg.essential.network.cosmetics.Cosmetic;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.dreamys.prometheus.essential.serial.EssentialCosmeticsFileData;

import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;

@Mixin(value = CosmeticsManager.class, remap = false)
public abstract class MixinCosmeticsManager {
    @Shadow
    @Final
    private @NotNull ConnectionManager connectionManager;

    @Shadow
    @Final
    private @NotNull InfraCosmeticsData infraCosmeticsData;

    @Shadow
    private boolean receivedUnlockPacket;

    @Shadow
    public abstract void addUnlockedCosmeticsData(@NotNull Map<String, CosmeticUnlockData> unlockedCosmeticsData);

    // Runs on every (re)connect, right after Essential wiped its cosmetic state.
    // Requesting legacy/expired cosmetics through InfraCosmeticsData (instead of sending the packet ourselves) marks them
    // as active requests, so cosmeticsLoaded waits for them and outfits are applied with them already present.
    @Inject(method = "resetState", at = @At("RETURN"))
    private void onResetState(CallbackInfo ci) {
        EssentialCosmeticsFileData.consumeUnrequested(); // everything is requested below anyway
        infraCosmeticsData.requestCosmeticsIfMissing(EssentialCosmeticsFileData.getCosmetics());
    }

    // The GitHub list is merged in the background and may land after we connected, request whatever it added.
    // Done on tick so InfraCosmeticsData is only ever touched from the main thread.
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(ClientTickEvent tickEvent, CallbackInfo ci) {
        if (connectionManager.isAuthenticated() && EssentialCosmeticsFileData.consumeUnrequested()) {
            infraCosmeticsData.requestCosmeticsIfMissing(EssentialCosmeticsFileData.getCosmetics());
        }
    }

    // Wraps the onNewCosmetic consumer registered in the constructor. Essential only unlocks new cosmetics there when sideloading,
    // we unlock every cosmetic the moment it lands in the cosmetics list, no matter how late its assets finish loading.
    // (wrapping the call instead of targeting the lambda, since lambda names differ between Essential builds)
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lgg/essential/network/connectionmanager/cosmetics/CosmeticsManagerKt;onNewCosmetic(Lgg/essential/network/connectionmanager/cosmetics/CosmeticsData;Lgg/essential/elementa/state/v2/ReferenceHolder;Ljava/util/function/Consumer;)V"), index = 2)
    private Consumer<Cosmetic> wrapOnNewCosmetic(Consumer<Cosmetic> consumer) {
        return cosmetic -> {
            consumer.accept(cosmetic);
            unlockCosmetic(cosmetic);
        };
    }

    @Unique
    private void unlockCosmetic(Cosmetic cosmetic) {
        // addUnlockedCosmeticsData marks the server's unlock packet as received, which would let cosmeticsLoaded flip early
        boolean receivedUnlockPacket = this.receivedUnlockPacket;
        addUnlockedCosmeticsData(Collections.singletonMap(cosmetic.getId(), new CosmeticUnlockData(new DateTime(), null, true)));
        this.receivedUnlockPacket = receivedUnlockPacket;
    }
}
