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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Mixin(value = CosmeticsManager.class, remap = false)
public abstract class MixinCosmeticsManager {

    @Shadow
    public abstract void unlockAllCosmetics();

    @Shadow
    @Final
    private ConnectionManager connectionManager;

    @Unique
    private boolean requested = false;

    @Unique
    private static final Set<String> EXTRA_COSMETICS = new HashSet<>(Arrays.asList(
            "KING_CROWN", "FROG_HAT", "PLANT", "BANDANA", "HEART_PARTICLES",
            "SOMBRERO", "CYBERG_ARM", "CYBER_ARM", "CYBORG_ARM", "PROPELLER_HAT",

            "LITTLE_ANGEL_WINGS", "FALLEN_ANGEL_WINGS",
            "LITTLE_DEMON_WINGS", "LITTLE_DEMONIC_WINGS", "LITTLE_DEMONIC_OVERLORD_WINGS",

            "HEAD_FLAMES", "FLAME_HALO", "RGB_FLAME_HALO",
            "BLOSSOM_PARTICLES", "CHERRY_BLOSSOM_PARTICLES",
            "SNOW_PARTICLES", "SNOWFLAKE_PARTICLES",

            "SPIRIT_FLAME_WINGS", "SPIRIT_FIRE_WINGS", "SPIRIT_WINGS",

            "MATRIX", "MATRIX_PARTICLES", "MATRIX_RAIN", "DIGITAL_RAIN", "DATA_STREAM",
            "FIRE_AURA", "FIRE_PARTICLES", "FLAME_AURA", "FLAME_PARTICLES",
            "ICE_AURA", "ICE_PARTICLES", "FROST_AURA", "FROST_PARTICLES",
            "WATER_AURA", "WATER_PARTICLES", "DARK_AURA", "SHADOW_AURA",
            "LIGHTNING_AURA", "ENERGY_AURA", "MAGIC_AURA",

            "MIXED_FIRE_FISTS", "MIXED_FIRE_FISTS_REMASTER",
            "MIXED_FIRE_EYES", "MIXED_FIRE_EYES_REMASTER",
            "MIXED_FIRE_ARMOR", "MIXED_FIRE_ARMOR_REMASTER",
            "MIXED_FIRE_ARMOR_HELMET", "MIXED_FIRE_ARMOR_HELMET_REMASTER",
            "MIXED_FIRE_FEET", "MIXED_FIRE_FEET_REMASTER",
            "MIXED_FIRE_WINGS", "MIXED_FIRE_WINGS_REMASTER",
            "MIXED_FIRE_AXE", "MIXED_FIRE_FLIGHT", "MIXED_FIRE_POWERS",
            "MIXED_FIRE_SCYTHE", "MIXED_FIRE_SWORD",
            "MIXED_FIRE_HALO", "MIXED_FIRE_PARTICLES", "MIXED_FIRE_CAPE",

            "THE_HOLLOW_SENTINEL", "HOLLOW_SENTINEL", "VOID_MATTER",
            "HOLLOW_SENTINEL_HELMET", "HOLLOW_SENTINEL_HEAD",
            "HOLLOW_SENTINEL_ARMOR", "HOLLOW_SENTINEL_BODY",
            "HOLLOW_SENTINEL_WINGS", "HOLLOW_SENTINEL_CAPE",
            "HOLLOW_SENTINEL_BLADE", "HOLLOW_SENTINEL_SWORD",

            "SCOUT_KNEEL", "SCOUT_KNEEL_EMOTE",
            "BLADE_CHALLENGE", "BLADE_CHALLENGE_EMOTE"
    ));

    @Inject(method = "tick", at = @At("RETURN"))
    public void onTick(ClientTickEvent tickEvent, CallbackInfo ci) {
        unlockAllCosmetics();

        if (!requested) {
            requested = true;
            connectionManager.send(new ClientCosmeticRequestPacket(EXTRA_COSMETICS, null));
        }
    }
}
