package studio.dreamys.prometheus.essential.verity;

import com.sparkuniverse.toolbox.util.DateTime;
import gg.essential.cosmetics.ExtensionsKt;
import gg.essential.cosmetics.model.CosmeticOutfit;
import gg.essential.cosmetics.model.CosmeticUnlockData;
import gg.essential.lib.gson.Gson;
import gg.essential.network.connectionmanager.ConnectionManager;
import gg.essential.network.connectionmanager.cosmetics.CosmeticsManager;
import gg.essential.network.cosmetics.ConversionsKt;
import gg.essential.network.cosmetics.Cosmetic;
import gg.essential.util.MagicPathsKt;
import ing.prometheusreengineer.verity.api.Verity;
import studio.dreamys.prometheus.essential.serial.EssentialCosmeticsFileData;
import studio.dreamys.prometheus.essential.serial.EssentialCosmeticsManager;
import studio.dreamys.prometheus.essential.serial.EssentialOutfitOverrides;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The in-game half of the Verity tests: once Essential is connected and has sent its cosmetics, checks that the patch
 * did its job on this Minecraft version and loader. Driven from {@code CosmeticsManager#tick} (see
 * {@code MixinCosmeticsManager}), so everything here runs on the main thread, where Essential's state lives. Does nothing
 * unless {@link VerityChecks} started it, which only happens when Verity launched the game.
 */
public final class GameChecks {
    /** Set once Verity is known to be running; the tick hook checks it before calling in. */
    public static volatile boolean active;

    private enum Phase { CONNECT, LOAD, SETTLE, DONE }

    private static final long PHASE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(Integer.getInteger("prometheus.verity.timeout", 240));
    /**
     * Every target signs in to Essential through Mojang's session server with the same account, at about the same
     * time; Mojang rate-limits that (HTTP 429) and Essential retries every 5 s, so on CI the last ones get through
     * minutes later.
     */
    private static final long CONNECT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(Integer.getInteger("prometheus.verity.connectTimeout", 900));
    /** The server keeps sending cosmetics (legacy ones answer our request) for a while after "loaded". */
    private static final long SETTLE_NANOS = TimeUnit.SECONDS.toNanos(Integer.getInteger("prometheus.verity.settle", 15));
    private static final int SURVIVE_SECONDS = Integer.getInteger("prometheus.verity.survive", 10);
    /** The share of the bundled legacy list Essential must still know; it removes a few cosmetics for good now and then. */
    private static final double LEGACY_RATIO = Double.parseDouble(System.getProperty("prometheus.verity.legacyRatio", "0.9"));
    static final String TEST_OUTFIT = "prometheus-verity-test";

    private static Phase phase = Phase.CONNECT;
    private static long phaseStart;
    private static int lastCount = -1;
    private static long lastChange;

    private GameChecks() {}

    /** Starts the checks; called once, from Essential's init. Offline (no account) only the survival check runs. */
    static void start(boolean online) {
        if (!online) {
            Verity.log("Offline run: the checks that need Essential's servers are skipped (no Microsoft account).");
            finishAfterSurviving();
            return;
        }
        phaseStart = System.nanoTime();
        active = true;
    }

    /** Called on every client tick by {@code MixinCosmeticsManager}, while {@link #active}. */
    public static void tick(CosmeticsManager cosmetics, ConnectionManager connection) {
        try {
            step(cosmetics, connection);
        } catch (Throwable t) {
            Verity.fail("in-game checks", t);
            done();
        }
    }

    private static void step(CosmeticsManager cosmetics, ConnectionManager connection) throws Exception {
        long now = System.nanoTime();
        long elapsed = now - phaseStart;
        switch (phase) {
            case CONNECT:
                if (connection.isOpen()) {
                    Verity.pass("connected to Essential's servers after " + seconds(elapsed));
                    next(Phase.LOAD, now);
                } else if (elapsed > CONNECT_TIMEOUT_NANOS) {
                    String replay = System.getProperty(VerityChecks.REPLAY_PROPERTY);
                    Verity.fail("connected to Essential's servers", "not connected after " + seconds(elapsed) + ". " + (replay != null
                            ? "Is cm-replay running at " + replay + " (and its join stub at minecraft.api.session.host)?"
                            : "Is the test account signed in (VERITY_MSA_REFRESH_TOKEN), are Essential's servers up,"
                            + " and does the log show Mojang rate-limiting (too many clients signing in at once)?"));
                    done();
                }
                return;
            case LOAD:
                if (Boolean.TRUE.equals(stateValue(cosmetics, "getCosmeticsLoaded"))) {
                    Verity.pass("Essential loaded its cosmetics after " + seconds(elapsed));
                    next(Phase.SETTLE, now);
                    lastChange = now;
                } else if (elapsed > PHASE_TIMEOUT_NANOS) {
                    Verity.fail("Essential loaded its cosmetics", "still loading after " + seconds(elapsed));
                    done();
                }
                return;
            case SETTLE:
                int count = cosmetics(cosmetics).size();
                if (count != lastCount) {
                    lastCount = count;
                    lastChange = now;
                }
                if (now - lastChange > SETTLE_NANOS || elapsed > PHASE_TIMEOUT_NANOS) {
                    evaluate(cosmetics);
                    done();
                }
                return;
            default:
        }
    }

    private static void next(Phase p, long now) {
        phase = p;
        phaseStart = now;
    }

    private static void done() {
        phase = Phase.DONE;
        active = false;
        finishAfterSurviving();
    }

    private static void evaluate(CosmeticsManager manager) throws Exception {
        final List<Cosmetic> loaded = cosmetics(manager);
        final Map<String, Cosmetic> byId = new LinkedHashMap<>();
        for (Cosmetic c : loaded) byId.put(c.getId(), c);
        @SuppressWarnings("unchecked") final Set<String> unlocked = (Set<String>) stateValue(manager, "getUnlockedCosmetics");
        @SuppressWarnings("unchecked") final Map<String, CosmeticUnlockData> unlockData =
                (Map<String, CosmeticUnlockData>) stateValue(manager, "getUnlockedCosmeticsData");
        final Set<String> bundled = bundledList();
        final Instant now = Instant.now();
        final List<Cosmetic> expired = new ArrayList<>();
        for (Cosmetic c : loaded) {
            Instant until = c.getAvailableUntil();
            if (until != null && until.isBefore(now)) expired.add(c);
        }
        final List<Cosmetic> priced = new ArrayList<>();
        for (Cosmetic c : loaded) {
            Integer price = c.getPrice();
            if (price != null && price > 0) priced.add(c);
        }

        check("legacy cosmetics arrive", () -> {
            Set<String> missing = new TreeSet<>(bundled);
            missing.removeAll(byId.keySet());
            int present = bundled.size() - missing.size();
            if (!missing.isEmpty()) Verity.log("Bundled legacy cosmetics Essential didn't send (" + missing.size() + "): " + cap(missing));
            if (present < bundled.size() * LEGACY_RATIO) {
                throw new AssertionError("Essential sent only " + present + " of the " + bundled.size()
                        + " bundled legacy cosmetics; missing: " + cap(missing));
            }
            if (expired.isEmpty()) throw new AssertionError("no expired cosmetics among the " + loaded.size() + " loaded");
        });

        check("every cosmetic is available", () -> {
            Method isAvailable = isAvailableMethod();
            List<String> unavailable = new ArrayList<>();
            for (Cosmetic c : loaded) {
                if (!Boolean.TRUE.equals(isAvailable.invoke(null, c, null))) unavailable.add(c.getId());
            }
            if (!unavailable.isEmpty()) throw new AssertionError("not available: " + cap(unavailable));
        });

        check("every cosmetic is unlocked", () -> {
            List<String> locked = new ArrayList<>();
            for (Cosmetic c : loaded) if (!unlocked.contains(c.getId())) locked.add(c.getId());
            if (!locked.isEmpty()) throw new AssertionError(locked.size() + " of " + loaded.size() + " locked: " + cap(locked));
        });

        // The wardrobe copies CosmeticsManager's unlocked set and lists a cosmetic as owned exactly when its ID is in it
        // (WardrobeState.unlockedCosmetics / getUnownedCosmetics), so paid cosmetics show as owned, not for sale.
        check("the wardrobe shows paid and expired cosmetics as owned", () -> {
            if (priced.isEmpty()) throw new AssertionError("no paid cosmetics among the " + loaded.size() + " loaded");
            List<String> forSale = new ArrayList<>();
            for (Cosmetic c : priced) if (!unlocked.contains(c.getId())) forSale.add(c.getId());
            for (Cosmetic c : expired) if (!unlocked.contains(c.getId())) forSale.add(c.getId());
            if (!forSale.isEmpty()) throw new AssertionError("not owned: " + cap(forSale));
        });

        check("unlocks for expired cosmetics come from Prometheus", () -> {
            List<String> wrong = new ArrayList<>();
            for (Cosmetic c : expired) {
                CosmeticUnlockData data = unlockData.get(c.getId());
                if (data == null || !data.isWardrobeUnlock()) wrong.add(c.getId());
            }
            if (!wrong.isEmpty()) throw new AssertionError("no wardrobe unlock: " + cap(wrong));
        });

        check("cosmetics.json records what Essential sent", () -> {
            Set<String> recorded = EssentialCosmeticsFileData.getCosmetics();
            Set<String> onDisk = readList(EssentialCosmeticsManager.PROMETHEUS_ESSENTIAL_FOLDER.resolve("cosmetics.json"));
            List<String> notRecorded = new ArrayList<>();
            List<String> notSaved = new ArrayList<>();
            for (String id : byId.keySet()) {
                if (!EssentialCosmeticsManager.isValidCosmeticId(id)) continue;
                if (!recorded.contains(id)) notRecorded.add(id);
                if (!onDisk.contains(id)) notSaved.add(id);
            }
            if (!notRecorded.isEmpty()) throw new AssertionError("not in the list: " + cap(notRecorded));
            if (!notSaved.isEmpty()) throw new AssertionError("not saved to cosmetics.json: " + cap(notSaved));
        });

        check("cosmetics are dumped", () -> {
            Set<String> dumped = new LinkedHashSet<>();
            try (Stream<Path> files = Files.walk(EssentialCosmeticsManager.DUMPS_PATH)) {
                for (Path p : files.filter(Files::isRegularFile).collect(Collectors.toList())) {
                    String name = p.getFileName().toString();
                    if (name.endsWith(".json")) dumped.add(name.substring(0, name.length() - 5));
                }
            }
            List<String> missing = new ArrayList<>();
            for (String id : byId.keySet()) {
                if (EssentialCosmeticsManager.isValidCosmeticId(id) && !dumped.contains(id)) missing.add(id);
            }
            if (!missing.isEmpty()) throw new AssertionError(missing.size() + " not dumped: " + cap(missing));
        });

        check("outfit overrides survive Essential's outfit sync", () -> {
            if (expired.isEmpty()) throw new AssertionError("no expired cosmetic to equip");
            Cosmetic legacy = expired.get(0);
            String slot = legacy.getSlot().getId();
            EssentialOutfitOverrides.setSlot(TEST_OUTFIT, slot, legacy.getId());
            try {
                // What the server sends after a restart: the outfit without the legacy cosmetic it can't store.
                CosmeticOutfit fromServer = new CosmeticOutfit(TEST_OUTFIT, "Verity", null, Collections.emptyMap(),
                        Collections.emptyMap(), false, new DateTime(), null, null);
                List<gg.essential.mod.cosmetics.CosmeticOutfit> converted =
                        ConversionsKt.outfitsToMod(Collections.singletonList(fromServer));
                String equipped = converted.get(0).getEquippedCosmetics().get(legacy.getSlot());
                if (!legacy.getId().equals(equipped)) {
                    throw new AssertionError("slot " + slot + " has " + equipped + " instead of " + legacy.getId());
                }
            } finally {
                EssentialOutfitOverrides.removeOutfit(TEST_OUTFIT);
            }
        });

        String summary = "cosmetics loaded: " + loaded.size() + "\nunlocked: " + unlocked.size()
                + "\nexpired (legacy) loaded: " + expired.size() + "\npaid: " + priced.size()
                + "\nbundled legacy list: " + bundled.size() + "\n";
        Verity.log(summary.replace('\n', ' ').trim());
        Verity.attach("summary.txt", summary.getBytes(StandardCharsets.UTF_8));
        Path list = EssentialCosmeticsManager.PROMETHEUS_ESSENTIAL_FOLDER.resolve("cosmetics.json");
        if (Files.exists(list)) Verity.attach("cosmetics.json", Files.readAllBytes(list));
    }

    /** A check body that may throw checked exceptions; any throw fails the check. */
    private interface Check {
        void run() throws Exception;
    }

    private static void check(String name, final Check body) {
        Verity.test(name, () -> {
            try {
                body.run();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** Keeps the game running a little longer, so crashes right after the checks still fail the run. */
    private static void finishAfterSurviving() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(SURVIVE_SECONDS * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            Verity.pass("game still running " + SURVIVE_SECONDS + "s after the checks");
            // Last, so Essential's log writer (asynchronous) has caught up with the whole session.
            if (Boolean.getBoolean("prometheus.verity.record")) attachRecording();
            Verity.finish();
        }, "prometheus-verity");
        t.setDaemon(true);
        t.start();
    }

    /** Record mode (the CI record job): the filtered session, for cm-replay to serve to the other targets. */
    private static void attachRecording() {
        check("session recorded for replay", () -> {
            Path log = Recording.newestLog(MagicPathsKt.getGlobalEssentialDirectory().resolve("infra-logs"));
            if (log == null) throw new AssertionError("Essential wrote no infra-logs/*.log");
            byte[] recording = Recording.record(log);
            Verity.attach(Recording.FILE, recording);
            Verity.log("Recorded " + recording.length + " bytes of Essential's session from " + log.getFileName());
        });
    }

    // Essential's State types live in elementa, which isn't on our compile classpath, so they're read reflectively.
    private static Object stateValue(CosmeticsManager manager, String getter) throws Exception {
        Object state = CosmeticsManager.class.getMethod(getter).invoke(manager);
        Class<?> stateClass = Class.forName("gg.essential.gui.elementa.state.v2.State", false, CosmeticsManager.class.getClassLoader());
        return stateClass.getMethod("get").invoke(state);
    }

    private static List<Cosmetic> cosmetics(CosmeticsManager manager) throws Exception {
        List<Cosmetic> list = new ArrayList<>();
        for (Object o : (Iterable<?>) stateValue(manager, "getCosmetics")) list.add((Cosmetic) o);
        return list;
    }

    private static Method isAvailableMethod() throws ReflectiveOperationException {
        Class<?> observedInstant = Class.forName("gg.essential.gui.elementa.state.v2.ObservedInstant", false,
                ExtensionsKt.class.getClassLoader());
        return ExtensionsKt.class.getMethod("isAvailable", Cosmetic.class, observedInstant);
    }

    private static Set<String> bundledList() throws IOException {
        InputStream in = GameChecks.class.getClassLoader().getResourceAsStream("cosmetics.json");
        if (in == null) throw new IOException("bundled cosmetics.json not found");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            String[] ids = new Gson().fromJson(reader, String[].class);
            return new LinkedHashSet<>(java.util.Arrays.asList(ids));
        }
    }

    private static Set<String> readList(Path file) throws IOException {
        String[] ids = new Gson().fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), String[].class);
        return ids == null ? Collections.<String>emptySet() : new LinkedHashSet<>(java.util.Arrays.asList(ids));
    }

    private static String cap(java.util.Collection<String> ids) {
        List<String> list = new ArrayList<>(ids);
        return list.size() <= 30 ? list.toString() : list.subList(0, 30) + " and " + (list.size() - 30) + " more";
    }

    private static String seconds(long nanos) {
        return TimeUnit.NANOSECONDS.toSeconds(nanos) + "s";
    }
}
