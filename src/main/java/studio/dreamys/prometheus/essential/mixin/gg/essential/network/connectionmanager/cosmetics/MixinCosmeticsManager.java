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

import java.util.LinkedHashMap;
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

    /** Set on every reset, cleared once the legacy cosmetics were requested from an authenticated connection. */
    @Unique
    private boolean legacyRequestPending;

    /** Cosmetics that arrived since the last tick, unlocked together (main thread only). */
    @Unique
    private final Map<String, CosmeticUnlockData> pendingUnlocks = new LinkedHashMap<>();

    // Runs on every (re)connect, right after Essential wiped its cosmetic state, but also when Essential gets disabled
    // (while disconnected), so the actual request is deferred to the next authenticated tick.
    @Inject(method = "resetState", at = @At("RETURN"))
    private void onResetState(CallbackInfo ci) {
        legacyRequestPending = true;
        pendingUnlocks.clear(); // those belonged to the old, now wiped, cosmetics state
    }

    // Runs before Essential's own tick logic decides whether cosmetics are loaded.
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(ClientTickEvent tickEvent, CallbackInfo ci) {
        flushPendingUnlocks();
        if (!connectionManager.isAuthenticated()) return;
        // The GitHub list is merged in the background and may also land after we connected.
        boolean downloadedNew = EssentialCosmeticsFileData.consumeUnrequested();
        if (legacyRequestPending || downloadedNew) {
            legacyRequestPending = false;
            // Requesting legacy/expired cosmetics through InfraCosmeticsData (instead of sending the packet ourselves) marks
            // them as active requests, so cosmeticsLoaded waits for them and outfits are applied with them already present.
            infraCosmeticsData.requestCosmeticsIfMissing(EssentialCosmeticsFileData.getCosmetics());
        }
    }

    // Wraps the onNewCosmetic consumer registered in the constructor. Essential only unlocks new cosmetics there when sideloading,
    // we unlock every cosmetic once it lands in the cosmetics list, no matter how late its assets finish loading.
    // (wrapping the call instead of targeting the lambda, since lambda names differ between Essential builds)
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lgg/essential/network/connectionmanager/cosmetics/CosmeticsManagerKt;onNewCosmetic(Lgg/essential/network/connectionmanager/cosmetics/CosmeticsData;Lgg/essential/elementa/state/v2/ReferenceHolder;Ljava/util/function/Consumer;)V"), index = 2)
    private Consumer<Cosmetic> wrapOnNewCosmetic(Consumer<Cosmetic> consumer) {
        return cosmetic -> {
            consumer.accept(cosmetic);
            // batched: every unlock copies the whole unlocked map and recomputes outfits/wardrobe state,
            // doing that once per cosmetic for 1000+ cosmetics hitches the main thread
            pendingUnlocks.put(cosmetic.getId(), new CosmeticUnlockData(new DateTime(), null, true));
        };
    }

    @Unique
    private void flushPendingUnlocks() {
        if (pendingUnlocks.isEmpty()) return;
        Map<String, CosmeticUnlockData> unlocks = new LinkedHashMap<>(pendingUnlocks);
        pendingUnlocks.clear();
        // addUnlockedCosmeticsData marks the server's unlock packet as received, which would let cosmeticsLoaded flip early
        boolean receivedUnlockPacket = this.receivedUnlockPacket;
        addUnlockedCosmeticsData(unlocks);
        this.receivedUnlockPacket = receivedUnlockPacket;
    }
}
