package studio.dreamys.prometheus.essential.serial;

import gg.essential.lib.gson.Gson;
import gg.essential.lib.gson.JsonElement;
import gg.essential.lib.gson.JsonObject;
import gg.essential.lib.gson.JsonParseException;
import gg.essential.lib.gson.reflect.TypeToken;
import gg.essential.mod.cosmetics.CosmeticOutfit;
import gg.essential.mod.cosmetics.CosmeticSlot;
import gg.essential.mod.cosmetics.settings.CosmeticSetting;
import gg.essential.network.cosmetics.ConversionsKt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import studio.dreamys.prometheus.essential.util.GsonUtil;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Locally remembered outfit changes. Essential's server doesn't persist legacy/expired cosmetics (and their settings)
 * in outfits, so they'd vanish on restart. Every slot/settings change made on this client is stored here and laid over
 * the outfits the server sends; once the server agrees with a change (i.e. it persisted it), the override is dropped.
 * Serializes as {@code {"outfitId": {"slots": {"slotId": "COSMETIC_ID"}, "settings": {"COSMETIC_ID": [...]}}}}.
 */
public class EssentialOutfitOverrides {
    private EssentialOutfitOverrides() {}

    private static final @NotNull Logger logger = Logger.getLogger("Prometheus - EOO");
    private static final Gson GSON = new Gson();
    private static final @NotNull Type FILE_TYPE = new TypeToken<LinkedHashMap<String, OutfitOverrides>>() {}.getType();
    private static final @NotNull Type SLOTS_TYPE = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
    private static final File OUTFITS_FILE =
            new File(EssentialCosmeticsManager.PROMETHEUS_ESSENTIAL_FOLDER.toFile(), "outfits.json");

    private static final class OutfitOverrides {
        /** slotId -> cosmeticId */
        Map<String, String> slots = new LinkedHashMap<>();
        /** cosmeticId -> settings, stored in Essential's infra (network) format since that one is plain Gson */
        Map<String, List<gg.essential.cosmetics.model.CosmeticSetting>> settings = new LinkedHashMap<>();

        boolean isEmpty() {
            return slots.isEmpty() && settings.isEmpty();
        }
    }

    /** Guarded by itself. outfitId -> overrides */
    private static final @NotNull Map<String, OutfitOverrides> overrides = load();

    private static @NotNull Map<String, OutfitOverrides> load() {
        Map<String, OutfitOverrides> loaded = new LinkedHashMap<>();
        try {
            if (!OUTFITS_FILE.exists()) return loaded;
            String json = new String(Files.readAllBytes(OUTFITS_FILE.toPath()), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) return loaded;
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject outfit = entry.getValue().getAsJsonObject();
                OutfitOverrides parsed;
                if (outfit.has("slots") || outfit.has("settings")) {
                    parsed = GSON.fromJson(outfit, OutfitOverrides.class);
                    if (parsed.slots == null) parsed.slots = new LinkedHashMap<>();
                    if (parsed.settings == null) parsed.settings = new LinkedHashMap<>();
                } else {
                    // old format, the object was just the slot map
                    parsed = new OutfitOverrides();
                    parsed.slots = GSON.fromJson(outfit, SLOTS_TYPE);
                }
                if (!parsed.isEmpty()) loaded.put(entry.getKey(), parsed);
            }
        } catch (IOException | JsonParseException | IllegalStateException e) {
            // done to avoid crashing future launches
            logger.log(Level.SEVERE, "outfits.json is unreadable, starting with no overrides", e);
        }
        return loaded;
    }

    // called by MixinOutfitManager whenever a slot is (un)equipped on this client
    public static void setSlot(@NotNull String outfitId, @NotNull String slotId, @Nullable String cosmeticId) {
        synchronized (overrides) {
            OutfitOverrides outfit = overrides.computeIfAbsent(outfitId, k -> new OutfitOverrides());
            // unequipping is always persisted by the server, so there's nothing to remember
            if (cosmeticId == null) outfit.slots.remove(slotId);
            else outfit.slots.put(slotId, cosmeticId);
            if (outfit.isEmpty()) overrides.remove(outfitId);
        }
        save();
    }

    // called by MixinOutfitManager whenever a cosmetic's settings change on this client
    public static void setSettings(@NotNull String outfitId, @NotNull String cosmeticId, @NotNull List<? extends CosmeticSetting> settings) {
        List<gg.essential.cosmetics.model.CosmeticSetting> infra = ConversionsKt.settingsToInfra(settings);
        synchronized (overrides) {
            OutfitOverrides outfit = overrides.computeIfAbsent(outfitId, k -> new OutfitOverrides());
            // an empty list is how Essential clears settings, which the server persists fine
            if (infra.isEmpty()) outfit.settings.remove(cosmeticId);
            else outfit.settings.put(cosmeticId, infra);
            if (outfit.isEmpty()) overrides.remove(outfitId);
        }
        save();
    }

    // called by MixinOutfitManager when an outfit is deleted
    public static void removeOutfit(@NotNull String outfitId) {
        boolean removed;
        synchronized (overrides) {
            removed = overrides.remove(outfitId) != null;
        }
        if (removed) save();
    }

    // called by MixinConversionsKt with the outfits the server just sent
    public static @NotNull List<CosmeticOutfit> apply(@NotNull List<CosmeticOutfit> outfits) {
        List<CosmeticOutfit> result = new ArrayList<>(outfits.size());
        boolean changed = false;
        synchronized (overrides) {
            if (overrides.isEmpty()) return outfits;
            for (CosmeticOutfit outfit : outfits) {
                OutfitOverrides outfitOverrides = overrides.get(outfit.getId());
                if (outfitOverrides == null) {
                    result.add(outfit);
                    continue;
                }

                Map<CosmeticSlot, String> equipped = new LinkedHashMap<>(outfit.getEquippedCosmetics());
                for (Iterator<Map.Entry<String, String>> it = outfitOverrides.slots.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<String, String> entry = it.next();
                    CosmeticSlot slot = CosmeticSlot.Companion.of(entry.getKey());
                    if (entry.getValue().equals(equipped.get(slot))) {
                        it.remove(); // server persisted it, no need to keep overriding
                        changed = true;
                    } else {
                        equipped.put(slot, entry.getValue());
                    }
                }

                Map<String, List<CosmeticSetting>> settings = new LinkedHashMap<>(outfit.getCosmeticSettings());
                for (Iterator<Map.Entry<String, List<gg.essential.cosmetics.model.CosmeticSetting>>> it = outfitOverrides.settings.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<String, List<gg.essential.cosmetics.model.CosmeticSetting>> entry = it.next();
                    String cosmeticId = entry.getKey();
                    if (!equipped.containsValue(cosmeticId)) {
                        it.remove(); // cosmetic isn't worn anymore, Essential drops its settings too
                        changed = true;
                        continue;
                    }
                    List<CosmeticSetting> modSettings;
                    try {
                        modSettings = ConversionsKt.settingsToModSetting(entry.getValue());
                    } catch (RuntimeException e) {
                        logger.log(Level.WARNING, "Dropping unreadable settings override for " + cosmeticId, e);
                        it.remove();
                        changed = true;
                        continue;
                    }
                    if (Objects.equals(modSettings, settings.get(cosmeticId))) {
                        it.remove(); // server persisted it, no need to keep overriding
                        changed = true;
                    } else {
                        settings.put(cosmeticId, modSettings);
                    }
                }

                if (outfitOverrides.isEmpty()) overrides.remove(outfit.getId());
                result.add(outfit.copy(outfit.getId(), outfit.getName(), outfit.getSkin(), outfit.getSkinId(), equipped,
                        settings, outfit.getFavoritedSince(), outfit.getCreatedAt()));
            }
        }
        if (changed) save();
        return result;
    }

    /** Snapshots the overrides on the caller's (main) thread and writes them on the IO thread. */
    private static void save() {
        String json;
        synchronized (overrides) {
            try {
                json = GsonUtil.toPrettyString(overrides, FILE_TYPE);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to serialize outfit overrides!", e);
                return;
            }
        }
        // single IO thread, so snapshots are written in the order they were taken
        EssentialCosmeticsManager.runIo(() -> write(json));
    }

    private static void write(@NotNull String json) {
        try {
            Path tmp = OUTFITS_FILE.toPath().resolveSibling(OUTFITS_FILE.getName() + ".tmp");
            try {
                Files.createDirectories(tmp.getParent());
                Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
                Files.move(tmp, OUTFITS_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save outfit overrides!", e);
        }
    }
}
