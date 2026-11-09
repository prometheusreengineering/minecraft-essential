package studio.dreamys.prometheus.essential.serial;

import gg.essential.lib.gson.Gson;
import gg.essential.lib.gson.JsonObject;
import gg.essential.mod.Model;
import gg.essential.mod.Skin;
import gg.essential.mod.cosmetics.CosmeticOutfit;
import gg.essential.mod.cosmetics.CosmeticSlot;
import gg.essential.mod.cosmetics.settings.CosmeticSetting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EssentialOutfitOverridesTest {
    private static final CosmeticSlot HAT = CosmeticSlot.Companion.of("HAT");
    private static final CosmeticSlot CAPE = CosmeticSlot.Companion.of("CAPE");

    @TempDir
    Path dir;
    private Path file;
    private final AtomicInteger writes = new AtomicInteger();
    /** Writes right away, counting them. */
    private final Executor io = task -> {
        writes.incrementAndGet();
        task.run();
    };

    private EssentialOutfitOverrides overrides() {
        file = dir.resolve("outfits.json");
        return new EssentialOutfitOverrides(file, io);
    }

    private EssentialOutfitOverrides reload() {
        return new EssentialOutfitOverrides(file, io);
    }

    private static CosmeticOutfit outfit(String id, Map<CosmeticSlot, String> equipped, Map<String, List<CosmeticSetting>> settings) {
        return new CosmeticOutfit(id, "Outfit " + id, new Skin("hash", Model.STEVE), "skin", equipped, settings, null, Instant.EPOCH);
    }

    private static Map<CosmeticSlot, String> slots(Object... slotAndId) {
        Map<CosmeticSlot, String> map = new LinkedHashMap<>();
        for (int i = 0; i < slotAndId.length; i += 2) map.put((CosmeticSlot) slotAndId[i], (String) slotAndId[i + 1]);
        return map;
    }

    private static List<CosmeticSetting> variant(String cosmeticId, String variant) {
        return Collections.singletonList(new CosmeticSetting.Variant(cosmeticId, true, new CosmeticSetting.Variant.Data(variant)));
    }

    private JsonObject saved() throws IOException {
        return new Gson().fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), JsonObject.class);
    }

    @Test
    void withNoOverridesTheServersOutfitsPassThroughUntouched() {
        EssentialOutfitOverrides overrides = overrides();
        List<CosmeticOutfit> server = Collections.singletonList(outfit("o1", slots(HAT, "A"), Collections.emptyMap()));

        assertSame(server, overrides.applyTo(server));
        assertEquals(0, writes.get());
    }

    @Test
    void aLegacyCosmeticTheServerDroppedIsPutBack() {
        EssentialOutfitOverrides overrides = overrides();
        overrides.putSlot("o1", "HAT", "LEGACY_HAT");

        // The server doesn't keep legacy cosmetics in outfits, so it sends the outfit without it.
        List<CosmeticOutfit> result = overrides.applyTo(Collections.singletonList(outfit("o1", slots(CAPE, "CAPE_1"), Collections.emptyMap())));

        assertEquals(slots(CAPE, "CAPE_1", HAT, "LEGACY_HAT"), result.get(0).getEquippedCosmetics());
        assertEquals("o1", result.get(0).getId());
        assertEquals("Outfit o1", result.get(0).getName(), "the rest of the outfit is kept");
    }

    @Test
    void theOverrideSurvivesARestart() {
        EssentialOutfitOverrides first = overrides();
        first.putSlot("o1", "HAT", "LEGACY_HAT");

        List<CosmeticOutfit> result = reload().applyTo(Collections.singletonList(outfit("o1", slots(), Collections.emptyMap())));

        assertEquals("LEGACY_HAT", result.get(0).getEquippedCosmetics().get(HAT));
    }

    @Test
    void onceTheServerAgreesTheOverrideIsDropped() throws IOException {
        EssentialOutfitOverrides overrides = overrides();
        overrides.putSlot("o1", "HAT", "NORMAL_HAT");
        int before = writes.get();

        overrides.applyTo(Collections.singletonList(outfit("o1", slots(HAT, "NORMAL_HAT"), Collections.emptyMap())));

        assertEquals(before + 1, writes.get(), "saved because the override went away");
        assertFalse(saved().has("o1"), "nothing left to remember for o1");
        List<CosmeticOutfit> server = Collections.singletonList(outfit("o1", slots(HAT, "OTHER"), Collections.emptyMap()));
        assertSame(server, overrides.applyTo(server), "the dropped override no longer applies");
    }

    @Test
    void otherOutfitsAreLeftAlone() {
        EssentialOutfitOverrides overrides = overrides();
        overrides.putSlot("o1", "HAT", "LEGACY_HAT");
        CosmeticOutfit other = outfit("o2", slots(HAT, "A"), Collections.emptyMap());

        List<CosmeticOutfit> result = overrides.applyTo(Arrays.asList(outfit("o1", slots(), Collections.emptyMap()), other));

        assertSame(other, result.get(1));
    }

    @Test
    void applyingWithoutChangesDoesNotSave() {
        EssentialOutfitOverrides overrides = overrides();
        overrides.putSlot("o1", "HAT", "LEGACY_HAT");
        int before = writes.get();

        overrides.applyTo(Collections.singletonList(outfit("o1", slots(), Collections.emptyMap())));

        assertEquals(before, writes.get());
    }

    @Test
    void unequippingRemovesTheSlotAndAnEmptyOutfitIsForgotten() throws IOException {
        EssentialOutfitOverrides overrides = overrides();
        overrides.putSlot("o1", "HAT", "LEGACY_HAT");
        overrides.putSlot("o1", "CAPE", "LEGACY_CAPE");
        overrides.putSlot("o1", "HAT", null);
        assertEquals("LEGACY_CAPE", saved().getAsJsonObject("o1").getAsJsonObject("slots").get("CAPE").getAsString());
        assertFalse(saved().getAsJsonObject("o1").getAsJsonObject("slots").has("HAT"));

        overrides.putSlot("o1", "CAPE", null);

        assertFalse(saved().has("o1"));
    }

    @Test
    void replacingASlotKeepsTheLatest() {
        EssentialOutfitOverrides overrides = overrides();
        overrides.putSlot("o1", "HAT", "FIRST");
        overrides.putSlot("o1", "HAT", "SECOND");

        List<CosmeticOutfit> result = overrides.applyTo(Collections.singletonList(outfit("o1", slots(), Collections.emptyMap())));

        assertEquals("SECOND", result.get(0).getEquippedCosmetics().get(HAT));
    }

    @Test
    void settingsOfAWornCosmeticAreApplied() {
        EssentialOutfitOverrides overrides = overrides();
        overrides.putSlot("o1", "HAT", "LEGACY_HAT");
        overrides.putSettings("o1", "LEGACY_HAT", variant("LEGACY_HAT", "red"));

        List<CosmeticOutfit> result = reload().applyTo(Collections.singletonList(outfit("o1", slots(), Collections.emptyMap())));

        assertEquals(variant("LEGACY_HAT", "red"), result.get(0).getCosmeticSettings().get("LEGACY_HAT"));
    }

    @Test
    void settingsOfACosmeticNoLongerWornAreDropped() throws IOException {
        EssentialOutfitOverrides overrides = overrides();
        overrides.putSettings("o1", "GONE", variant("GONE", "red"));

        List<CosmeticOutfit> result = overrides.applyTo(Collections.singletonList(outfit("o1", slots(HAT, "A"), Collections.emptyMap())));

        assertNull(result.get(0).getCosmeticSettings().get("GONE"));
        assertFalse(saved().has("o1"));
    }

    @Test
    void settingsTheServerAlreadyHasAreDropped() throws IOException {
        EssentialOutfitOverrides overrides = overrides();
        overrides.putSettings("o1", "A", variant("A", "red"));
        Map<String, List<CosmeticSetting>> serverSettings = new LinkedHashMap<>();
        serverSettings.put("A", variant("A", "red"));

        overrides.applyTo(Collections.singletonList(outfit("o1", slots(HAT, "A"), serverSettings)));

        assertFalse(saved().has("o1"));
    }

    @Test
    void clearingSettingsRemovesTheOverride() throws IOException {
        EssentialOutfitOverrides overrides = overrides();
        overrides.putSlot("o1", "HAT", "A");
        overrides.putSettings("o1", "A", variant("A", "red"));
        overrides.putSettings("o1", "A", Collections.emptyList());

        assertFalse(saved().getAsJsonObject("o1").getAsJsonObject("settings").has("A"));
    }

    @Test
    void deletingAnOutfitForgetsItsOverrides() throws IOException {
        EssentialOutfitOverrides overrides = overrides();
        overrides.putSlot("o1", "HAT", "A");
        overrides.putSlot("o2", "HAT", "B");

        overrides.dropOutfit("o1");

        assertFalse(saved().has("o1"));
        assertTrue(saved().has("o2"));
        int before = writes.get();
        overrides.dropOutfit("unknown");
        assertEquals(before, writes.get(), "nothing removed, nothing saved");
    }

    @Test
    void theOldSlotsOnlyFormatStillLoads() throws IOException {
        file = dir.resolve("outfits.json");
        Files.write(file, "{\"o1\": {\"HAT\": \"OLD_HAT\"}, \"empty\": {}}".getBytes(StandardCharsets.UTF_8));

        List<CosmeticOutfit> result = reload().applyTo(Arrays.asList(
                outfit("o1", slots(), Collections.emptyMap()), outfit("empty", slots(HAT, "X"), Collections.emptyMap())));

        assertEquals("OLD_HAT", result.get(0).getEquippedCosmetics().get(HAT));
        assertEquals("X", result.get(1).getEquippedCosmetics().get(HAT), "an empty entry is ignored");
    }

    @Test
    void anUnreadableFileStartsEmpty() throws IOException {
        for (String broken : new String[] {"{not json", "[1, 2]", "{\"o1\": 5}", "null"}) {
            file = dir.resolve("outfits.json");
            Files.write(file, broken.getBytes(StandardCharsets.UTF_8));
            List<CosmeticOutfit> server = Collections.singletonList(outfit("o1", slots(), Collections.emptyMap()));

            assertSame(server, reload().applyTo(server), broken);
        }
    }

    @Test
    void savingLeavesNoTempFile() {
        overrides().putSlot("o1", "HAT", "A");
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(dir.resolve("outfits.json.tmp")));
    }
}
