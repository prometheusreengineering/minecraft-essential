package studio.dreamys.prometheus.essential.serial;

import com.sparkuniverse.toolbox.util.DateTime;
import gg.essential.cosmetics.model.Cosmetic;
import gg.essential.cosmetics.model.CosmeticTier;
import gg.essential.lib.gson.Gson;
import gg.essential.lib.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class EssentialCosmeticsManagerTest {
    private static final boolean WINDOWS = File.separatorChar == '\\';

    @TempDir
    Path dir;

    static Cosmetic cosmetic(String id, String type) {
        Map<String, Double> prices = new HashMap<>();
        prices.put("coins", 500.0);
        return new Cosmetic(id, type, Collections.singletonMap("en_us", "Name of " + id), 7, prices,
                Collections.singleton("tag"), new DateTime(1_600_000_000_000L), new DateTime(1_600_000_000_000L),
                new DateTime(1_700_000_000_000L), null, Collections.emptyMap(), Collections.singletonMap("hats", 3),
                10, 500, CosmeticTier.RARE, Collections.emptyMap());
    }

    @Test
    void cosmeticIdsArePlainUpperCaseNames() {
        for (String ok : new String[] {"A", "121_CAPE", "3D_GLASSES", "Y2K_TRENDS_TOP", "67"}) {
            assertTrue(EssentialCosmeticsManager.isValidCosmeticId(ok), ok);
        }
        for (String bad : new String[] {"", "lowercase", "Mixed_Case", "WITH SPACE", "../ESCAPE", "A/B", "A-B",
                "0b6f5c1e-8f2a-4c1e-9a52-6c0f1a9d3e77", "ESSENTIAL_PURCHASE_CONFIRMATION", "essential_purchase_confirmation"}) {
            assertFalse(EssentialCosmeticsManager.isValidCosmeticId(bad), bad);
        }
    }

    @Test
    void onlyPlainTypesBecomeDumpFolders() {
        Path dumps = dir.resolve("dumps");
        assertEquals(dumps.resolve("HAT").resolve("TOP_HAT.json"), EssentialCosmeticsManager.dumpPath(dumps, "HAT", "TOP_HAT"));
        for (String bad : new String[] {"..", "../..", "HAT/../..", "hat", "", "C:", "A\\B"}) {
            assertNull(EssentialCosmeticsManager.dumpPath(dumps, bad, "X"), bad);
        }
    }

    @Test
    void aDumpIsTheCosmeticsJsonAndUnchangedDumpsAreNotRewritten() throws IOException {
        Path dump = dir.resolve("dumps/HAT/TOP_HAT.json");
        EssentialCosmeticsManager.dumpCosmetic(cosmetic("TOP_HAT", "HAT"), dump);

        // Essential's own (minified) field names, the same as its network format: a = id, b = type, o = coin price.
        JsonObject json = new Gson().fromJson(new String(Files.readAllBytes(dump), StandardCharsets.UTF_8), JsonObject.class);
        assertEquals("TOP_HAT", json.get("a").getAsString());
        assertEquals("HAT", json.get("b").getAsString());
        assertEquals(500, json.get("o").getAsInt());

        long written = Files.getLastModifiedTime(dump).toMillis();
        Files.setLastModifiedTime(dump, java.nio.file.attribute.FileTime.fromMillis(written - 60_000));
        EssentialCosmeticsManager.dumpCosmetic(cosmetic("TOP_HAT", "HAT"), dump);
        assertEquals(written - 60_000, Files.getLastModifiedTime(dump).toMillis(), "same content, not rewritten");

        Files.write(dump, "stale".getBytes(StandardCharsets.UTF_8));
        EssentialCosmeticsManager.dumpCosmetic(cosmetic("TOP_HAT", "HAT"), dump);
        assertTrue(new String(Files.readAllBytes(dump), StandardCharsets.UTF_8).contains("TOP_HAT"), "changed content is rewritten");
    }

    @Test
    void windowsGetsTheGlobalFolderAndNoLink() throws IOException {
        Path local = dir.resolve("instance/prometheus");
        Path essential = dir.resolve("global/prometheus/essential");

        EssentialCosmeticsManager.setupFolders(local, essential, true);

        assertTrue(Files.isDirectory(essential.resolve("dumps")));
        assertFalse(Files.exists(local, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void elsewhereTheInstanceFolderLinksToTheGlobalOne() throws IOException {
        assumeFalse(WINDOWS, "symlinks need privileges on Windows, and the game doesn't make them there");
        Path local = dir.resolve("instance/prometheus");
        Files.createDirectories(local.getParent());
        Path essential = dir.resolve("global/prometheus/essential");

        EssentialCosmeticsManager.setupFolders(local, essential, false);

        assertTrue(Files.isSymbolicLink(local));
        assertEquals(essential.getParent(), Files.readSymbolicLink(local));
        assertTrue(Files.isDirectory(local.resolve("essential/dumps")), "reachable through the link");
    }

    @Test
    void aBrokenLinkIsReplaced() throws IOException {
        assumeFalse(WINDOWS, "symlinks need privileges on Windows");
        Path local = dir.resolve("instance/prometheus");
        Files.createDirectories(local.getParent());
        Files.createSymbolicLink(local, dir.resolve("gone"));
        Path essential = dir.resolve("global/prometheus/essential");

        EssentialCosmeticsManager.tryCreateSymlinks(local, essential, false);

        assertEquals(essential.getParent(), Files.readSymbolicLink(local));
    }

    @Test
    void anExistingInstanceFolderIsLeftAlone() throws IOException {
        Path local = Files.createDirectories(dir.resolve("instance/prometheus"));
        Path essential = dir.resolve("global/prometheus/essential");

        EssentialCosmeticsManager.tryCreateSymlinks(local, essential, false);

        assertFalse(Files.isSymbolicLink(local));
        assertFalse(Files.exists(essential), "nothing is created when the folder is already there");
    }

    @Test
    void oldDumpsMoveToTheGlobalFolderAndTheirEmptyFoldersGo() throws IOException {
        Path local = dir.resolve("instance/prometheus");
        Path old = Files.createDirectories(local.resolve("dumps/essential/HAT"));
        Files.write(old.resolve("TOP_HAT.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(Files.createDirectories(local.resolve("keep")).resolve("other.txt"), new byte[] {1});
        Path essential = dir.resolve("global/prometheus/essential");

        EssentialCosmeticsManager.setupFolders(local, essential, true);

        assertTrue(Files.exists(essential.resolve("dumps/HAT/TOP_HAT.json")), "migrated");
        assertFalse(Files.exists(local.resolve("dumps")), "the emptied old folders are deleted");
        assertTrue(Files.exists(local.resolve("keep/other.txt")), "unrelated files stay");
    }

    @Test
    void anInstanceFolderEmptiedByTheMigrationIsRemoved() throws IOException {
        Path local = dir.resolve("instance/prometheus");
        Files.write(Files.createDirectories(local.resolve("dumps/essential/HAT")).resolve("A.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Path essential = dir.resolve("global/prometheus/essential");

        EssentialCosmeticsManager.setupFolders(local, essential, true);

        assertFalse(Files.exists(local, LinkOption.NOFOLLOW_LINKS), "nothing is left in it, so it goes (Windows: no link replaces it)");
        assertTrue(Files.exists(essential.resolve("dumps/HAT/A.json")));
    }

    @Test
    void aLocalEssentialFolderIsMergedWithoutOverwritingThenRenamed() throws IOException {
        Path local = dir.resolve("instance/prometheus");
        Path localEssential = Files.createDirectories(local.resolve("essential"));
        Files.write(localEssential.resolve("cosmetics.json"), "[\"LOCAL\"]".getBytes(StandardCharsets.UTF_8));
        Files.write(localEssential.resolve("outfits.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Path essential = Files.createDirectories(dir.resolve("global/prometheus/essential"));
        Files.write(essential.resolve("cosmetics.json"), "[\"GLOBAL\"]".getBytes(StandardCharsets.UTF_8));

        EssentialCosmeticsManager.migrateLocalEssentialFolder(local, essential);

        assertEquals("[\"GLOBAL\"]", new String(Files.readAllBytes(essential.resolve("cosmetics.json")), StandardCharsets.UTF_8),
                "the global copy wins");
        assertTrue(Files.exists(essential.resolve("outfits.json")), "missing files are copied");
        assertFalse(Files.exists(localEssential));
        assertTrue(Files.exists(local.resolve("essential.migrated/cosmetics.json")), "renamed so it runs once");
    }

    @Test
    void anEarlierMigrationIsNotOverwritten() throws IOException {
        Path local = dir.resolve("instance/prometheus");
        Files.createDirectories(local.resolve("essential"));
        Path earlier = Files.createDirectories(local.resolve("essential.migrated"));
        Files.write(earlier.resolve("marker"), new byte[] {1});
        Path essential = dir.resolve("global/prometheus/essential");

        EssentialCosmeticsManager.migrateLocalEssentialFolder(local, essential);

        assertTrue(Files.exists(earlier.resolve("marker")), "the earlier migration's copy stays");
        assertTrue(Files.isDirectory(local.resolve("essential")), "left in place rather than clobbering it");
        assertTrue(Files.isDirectory(essential));
    }
}
