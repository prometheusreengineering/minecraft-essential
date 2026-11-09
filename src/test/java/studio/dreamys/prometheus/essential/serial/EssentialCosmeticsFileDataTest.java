package studio.dreamys.prometheus.essential.serial;

import com.sun.net.httpserver.HttpServer;
import gg.essential.lib.gson.Gson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EssentialCosmeticsFileDataTest {
    private static final URI UNUSED = URI.create("http://127.0.0.1:9/unused");

    @TempDir
    Path dir;
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private static Callable<InputStream> bundled(String json) {
        return () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] read(Path file) throws IOException {
        return new Gson().fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), String[].class);
    }

    private static Set<String> setOf(String... ids) {
        return new LinkedHashSet<>(Arrays.asList(ids));
    }

    @Test
    void firstRunSeedsTheCacheFromTheBundledList() throws IOException {
        Path file = dir.resolve("prometheus/essential/cosmetics.json");
        EssentialCosmeticsFileData data = new EssentialCosmeticsFileData(file, bundled("[\"B\", \"A\"]"), UNUSED);

        assertEquals(setOf("B", "A"), data.cosmetics());
        assertTrue(Files.exists(file), "the bundled list is copied to the cache");
        data.save();
        assertArrayEquals(new String[] {"B", "A"}, read(file), "a clean list isn't rewritten");
    }

    @Test
    void theCachedListWinsOverTheBundledOne() throws IOException {
        Path file = dir.resolve("cosmetics.json");
        Files.write(file, "[\"CACHED\"]".getBytes(StandardCharsets.UTF_8));

        EssentialCosmeticsFileData data = new EssentialCosmeticsFileData(file, bundled("[\"BUNDLED\"]"), UNUSED);

        assertEquals(setOf("CACHED"), data.cosmetics());
    }

    @Test
    void aCorruptOrNullCacheFallsBackToTheBundledListAndIsRewritten() throws IOException {
        for (String broken : new String[] {"{not json", "null", "", "{\"an\": \"object\"}"}) {
            Path file = dir.resolve("cosmetics-" + Math.abs(broken.hashCode()) + ".json");
            Files.write(file, broken.getBytes(StandardCharsets.UTF_8));

            EssentialCosmeticsFileData data = new EssentialCosmeticsFileData(file, bundled("[\"BUNDLED\"]"), UNUSED);

            assertEquals(setOf("BUNDLED"), data.cosmetics(), "cache: " + broken);
            data.save();
            assertArrayEquals(new String[] {"BUNDLED"}, read(file), "the broken cache is overwritten: " + broken);
        }
    }

    @Test
    void aMissingOrBrokenBundledListStartsEmpty() {
        Path file = dir.resolve("cosmetics.json");
        EssentialCosmeticsFileData missing = new EssentialCosmeticsFileData(file, () -> null, UNUSED);
        assertTrue(missing.cosmetics().isEmpty());

        EssentialCosmeticsFileData broken = new EssentialCosmeticsFileData(dir.resolve("other.json"), bundled("{nope"), UNUSED);
        assertTrue(broken.cosmetics().isEmpty());
    }

    @Test
    void addingReportsWhatIsNewAndSavingWritesASortedList() throws IOException {
        Path file = dir.resolve("cosmetics.json");
        EssentialCosmeticsFileData data = new EssentialCosmeticsFileData(file, bundled("[\"M\"]"), UNUSED);

        assertTrue(data.add("Z"));
        assertFalse(data.add("Z"), "a known ID isn't new");
        assertEquals(2, data.addAll("A", "M", "B", "A"), "M is known and A is counted once");
        data.save();

        assertArrayEquals(new String[] {"A", "B", "M", "Z"}, read(file));
        assertFalse(Files.exists(dir.resolve("cosmetics.json.tmp")), "no temp file is left behind");
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(text.contains("\n"), "pretty printed");
    }

    @Test
    void savingWithoutChangesLeavesTheFileAlone() throws IOException {
        Path file = dir.resolve("cosmetics.json");
        EssentialCosmeticsFileData data = new EssentialCosmeticsFileData(file, bundled("[\"A\"]"), UNUSED);
        data.add("B");
        data.save();
        Files.write(file, "[\"EDITED\"]".getBytes(StandardCharsets.UTF_8));

        data.save();

        assertArrayEquals(new String[] {"EDITED"}, read(file));
    }

    @Test
    void aFailedSaveIsRetriedOnTheNextSave() throws IOException {
        Path file = dir.resolve("cosmetics.json");
        EssentialCosmeticsFileData data = new EssentialCosmeticsFileData(file, bundled("[\"A\"]"), UNUSED);
        data.add("B");
        // A directory where the temp file goes makes the write fail.
        Path tmp = dir.resolve("cosmetics.json.tmp");
        Files.createDirectories(tmp.resolve("blocker"));

        data.save();
        assertArrayEquals(new String[] {"A"}, read(file), "the failed save changed nothing");

        Files.delete(tmp.resolve("blocker"));
        Files.delete(tmp);
        data.save();
        assertArrayEquals(new String[] {"A", "B"}, read(file), "the change was still pending");
    }

    @Test
    void getCosmeticsReturnsACopy() {
        EssentialCosmeticsFileData data = new EssentialCosmeticsFileData(dir.resolve("c.json"), bundled("[\"A\"]"), UNUSED);
        data.cosmetics().add("B");
        assertEquals(setOf("A"), data.cosmetics());
    }

    private URI serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cosmetics.json", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cosmetics.json");
    }

    @Test
    void theDownloadMergesWithoutRemovingAndFlagsNewCosmeticsOnce() throws IOException {
        Path file = dir.resolve("cosmetics.json");
        URI url = serve(200, "[\"A\", \"NEW_1\", \"NEW_2\"]");
        EssentialCosmeticsFileData data = new EssentialCosmeticsFileData(file, bundled("[\"A\", \"LOCAL_ONLY\"]"), url);
        assertFalse(data.consumeUnrequestedFlag());

        assertEquals(2, data.download());

        assertEquals(setOf("A", "LOCAL_ONLY", "NEW_1", "NEW_2"), data.cosmetics(), "union, nothing removed");
        assertArrayEquals(new String[] {"A", "LOCAL_ONLY", "NEW_1", "NEW_2"}, read(file), "saved after the merge");
        assertTrue(data.consumeUnrequestedFlag(), "new IDs must be requested from Essential");
        assertFalse(data.consumeUnrequestedFlag(), "the flag clears once consumed");

        assertEquals(0, data.download());
        assertFalse(data.consumeUnrequestedFlag(), "nothing new, nothing to request");
    }

    @Test
    void aFailedDownloadChangesNothing() throws IOException {
        List<String[]> cases = Arrays.asList(
                new String[] {"404", "[\"X\"]"},
                new String[] {"200", "{not json"},
                new String[] {"200", "null"});
        for (String[] c : cases) {
            stopServer();
            Path file = dir.resolve("cosmetics-" + c[0] + Math.abs(c[1].hashCode()) + ".json");
            EssentialCosmeticsFileData data = new EssentialCosmeticsFileData(file, bundled("[\"A\"]"),
                    serve(Integer.parseInt(c[0]), c[1]));

            assertEquals(-1, data.download(), "HTTP " + c[0] + " " + c[1]);
            assertEquals(setOf("A"), data.cosmetics());
            assertFalse(data.consumeUnrequestedFlag());
        }
    }

    @Test
    void anUnreachableServerChangesNothing() {
        EssentialCosmeticsFileData data = new EssentialCosmeticsFileData(dir.resolve("c.json"), bundled("[\"A\"]"),
                URI.create("http://127.0.0.1:1/cosmetics.json"));
        assertEquals(-1, data.download());
        assertEquals(setOf("A"), data.cosmetics());
    }

    @Test
    void theShippedListIsValid() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("cosmetics.json")) {
            String[] ids = new Gson().fromJson(new String(readAll(in), StandardCharsets.UTF_8), String[].class);
            assertTrue(ids.length > 100, "the bundled list has the known legacy cosmetics");
            assertEquals(ids.length, new LinkedHashSet<>(Arrays.asList(ids)).size(), "no duplicates");
            for (String id : ids) assertTrue(EssentialCosmeticsManager.isValidCosmeticId(id), "valid ID: " + id);
            String[] sorted = ids.clone();
            Arrays.sort(sorted);
            assertArrayEquals(sorted, ids, "sorted, as the game saves it, so downloads don't churn the diff");
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
