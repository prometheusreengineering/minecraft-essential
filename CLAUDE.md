# CLAUDE.md

Prometheus patch for Essential Mod: a set of Mixins that unlock every Essential cosmetic client-side, dump cosmetic data, and keep legacy (removed/expired) cosmetics working. Read [CONTRIBUTING.md](CONTRIBUTING.md) for entrypoint design, commit style (Conventional Commits) and the project's AI policy.

## Layout

- `mixin/gg/essential/...` mirrors the package of the Essential class each mixin targets. Every mixin must be listed in `src/main/resources/prometheus.essential.mixins.json` (`defaultRequire: 1`, so a missing target crashes on launch).
- `serial/` holds our own state, persisted under `OS.getConfigFolder()/essential/`. This folder is global, not per instance: `%APPDATA%/.minecraft/prometheus` on Windows, the per-OS data folder elsewhere, overridable with `PROMETHEUS_FOLDER`. Never resolve it against the working directory. An instance's old local `prometheus/essential` is merged in once (without overwriting) and renamed to `essential.migrated`:
  - `EssentialCosmeticsFileData`: the legacy cosmetic ID list (`cosmetics.json`). It's seeded from the bundled resource and merged from the GitHub copy in the background at startup.
  - `EssentialCosmeticsManager`: folder setup, plus cosmetic dumps written on a single background IO thread.
  - `EssentialOutfitOverrides`: outfit slot/settings changes made on this client (`outfits.json`). These are laid over server outfits, because the server drops legacy cosmetics from saved outfits. An override is dropped once the server agrees with it.
- Loader entrypoints (`fabric/`, `forge/`) only register the mixin config. Real init happens in `MixinEssential` (`Essential#init`).

## How unlocking works (non-obvious)

- Essential's outfit, emote and cape code is reactive. Worn cosmetics are `outfit.equipped ∩ unlockedCosmetics`, so a cosmetic only needs to be in the unlocked set at some point. Nothing client-side strips it.
- Essential adds cosmetics **asynchronously**, after their settings asset loads. Never "unlock everything that's loaded now" (a snapshot misses late arrivals). `MixinCosmeticsManager` wraps the `onNewCosmetic` consumer and unlocks each cosmetic as it arrives.
- Request legacy IDs via `InfraCosmeticsData.requestCosmeticsIfMissing(...)`, never by sending `ClientCosmeticRequestPacket` yourself. That way they count as active requests, so `cosmeticsLoaded` waits for them. Responses still reach `ServerCosmeticsPopulatePacketHandler`.
- Packet handlers' `onHandle` runs on the **main thread**. Do no disk I/O there; use the IO executor in `EssentialCosmeticsManager`.

## Mixin rules (learned the hard way)

- `lib/essential.mock.jar` is the **Forge 1.8.9** build. Runtime builds (other MC versions, Fabric) differ in javac lambda numbering, so **never target `lambda$...` methods**; that crashed Fabric. Target named methods or `INVOKE` sites, or wrap a lambda by `@ModifyArg` on the call that receives it.
- Avoid `LocalCapture`. `CAPTURE_FAILSOFT` silently skips if locals differ. Prefer `@ModifyArg` to read call arguments.
- The mock jar has **no Kotlin stdlib or elementa on the compile classpath** (they're nested in `META-INF/jars`). Mixin/handler signatures may only use Java types plus Essential classes. Kotlin/elementa types can appear only inside target descriptor strings.
- Before adding or changing a target, verify it exists in every Essential build you can find, e.g. the user's Prism instances at `~/AppData/Roaming/PrismLauncher/instances/*/minecraft/essential/Essential (*).jar`:
  `unzip` the class, then `javap -p -c -cp <dir> <class> | grep <target>`.

## Reading Essential's code

The jar has class files only. Decompile with IntelliJ's bundled Vineflower, which needs Java 25, i.e. IntelliJ's JBR; the system JDK is 21:
```
"C:/Program Files/JetBrains/IntelliJ IDEA 2025.3/jbr/bin/java.exe" -cp "C:/Program Files/JetBrains/IntelliJ IDEA 2025.3/plugins/java-decompiler/lib/java-decompiler.jar" org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler -dgs=1 <classes-dir>/ <out-dir>/
```
Extract only the packages you need into a scratch dir first; the jar holds ~6300 classes. Upstream source: https://github.com/SparkUniverse/Essential-Mod.

## Building / checking

- Maven isn't on PATH on this machine (the Nix flake provides it elsewhere). To type-check without it, run `javac` against the mock jar plus `~/.m2` deps. Use `-proc:none`, because the Mixin annotation processor needs Guava; use `--release 8`; and pass Windows paths (`cygpath -w`) in the classpath:
  ```
  javac -proc:none --release 8 -cp "lib/essential.mock.jar;<annotations>;<mixin>;<gson>;<fabric-loader>;<universalfml>" -d <out> $(find src/main/java -name "*.java")
  ```
- "unknown enum constant AnnotationRetention/AnnotationTarget" warnings are expected (Kotlin annotations without the stdlib).
- Unit tests: `mvn package` runs them (JUnit 5, `src/test/java`; CI's build job too). On this machine use IntelliJ's Maven: `"/c/Program Files/JetBrains/IntelliJ IDEA 2025.3/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" -B package`. They run real Essential classes from `lib/essential.mock.jar`; the antrun step unpacks the libraries nested in it (Kotlin, elementa, ...) into `target/test-libs`, and surefire sets `PROMETHEUS_FOLDER` to `target/test-prometheus` so nothing reaches the real config. The `serial/` classes take their paths through package-private constructors/overloads for this; `current()` is the game's instance, created on first use. `MixinTargetsTest` checks every injector, `@Shadow` and `@Overwrite` against the mock jar's bytecode (method exists, the `INVOKE` target is really called there), so an Essential update that renames something fails the build. `util/` isn't tested (minecraft-lib replaces it).
- In-game checks run through [Verity](https://github.com/prometheusreengineering/verity) (`verity.json`, `.github/workflows/verity.yml`) against the Essential build pinned in `verity.json`, per Minecraft version and loader it ships for on Modrinth, headless on Linux and Windows, signed in with a test Microsoft account. Mojang rate-limits that account's sign-ins, so only one client signs in per run, and the rest replay its session:
  - The `record` job runs `verity-record.json` (one real client, `-Dprometheus.verity.record=true`). After every check it attaches `recording.jsonl`, which is Essential's own packet log (`<global Essential dir>/infra-logs/*.log`) filtered by `verity/Recording.java` to the cosmetics side. Chat, friends and profile packets are dropped, and so is anything else about the account, so keep that allowlist narrow. The recording is uploaded for a day.
  - The `verity` shards run `verity-replay.json` (every target, an offline account, no token or environment) against `tools/cm-replay` (a separate Maven module, Java 17, Java-WebSocket). It serves the recording on `ws://127.0.0.1:47801`: the recorded pushes on connect, and the recorded reply to each request, matched by name and canonical JSON, or by name when the recording has one such request. Requests it has no answer for are listed in the job summary. It also answers Mojang's `join` on `http://127.0.0.1:47802`. `VerityChecks.redirect` points Essential at it by setting `Connection.CM_HOST_URI`, a non-final static: `essential.cm.host` only accepts `wss://*.essential.gg`. `minecraft.api.session.host` sends Essential's own `MinecraftSessionService` join to the stub. Keep the replay profile's mods and targets in step with `verity.json`.
  - The nightly schedule runs all of `verity.json` against the real servers instead, as the reference the replay is checked against.

  The token (`verity account ci-token <name>`) is `VERITY_MSA_REFRESH_TOKEN` in two environments, never a repository secret: `verity-account` (required reviewers) for pull requests and manual runs off main, and `verity-account-main` (no reviewers, deployment branches limited to main) for main and the nightly run. The record job picks the environment by event and ref; `pull_request_target` runs have main's ref, so they're excluded by event and must stay that way. Pull requests from this repo's branches run through `pull_request`; forks through `pull_request_target` (main's copy of the workflow, the PR's head checked out, an extra approval before their code even builds, and caches restored but never saved, since those runs count as main). Don't add steps that run PR code with secrets outside the environment, or that save caches on `pull_request_target`. Targets without the pinned build are skipped, and Prometheus crashes on 1.4.1.1, so bump the pin (and `ESSENTIAL_VERSION` in the workflow) with each Essential release.
  - `verity/VerityChecks.java` runs at `Essential#init` RETURN (a no-op outside Verity): it loads each mixin target and asserts our `@MixinMerged` methods are there, so new mixins in the config are covered automatically, then starts `GameChecks`.
  - `verity/GameChecks.java` runs on the main thread from `MixinCosmeticsManager`'s tick hook (only while `GameChecks.active`): waits for the connection and for Essential's cosmetics to load and settle, then checks that the bundled legacy cosmetics arrive, every cosmetic is available and unlocked, the wardrobe shows paid and expired ones as owned (it lists a cosmetic as owned exactly when its ID is in `CosmeticsManager.getUnlockedCosmetics()`), `cosmetics.json` and the dumps record what Essential sent, and an outfit override survives `ConversionsKt.outfitsToMod`. It never sends outfit changes to the server, so parallel clients don't edit the shared account. Essential's `State` types (elementa) are read reflectively. Before connecting it accepts Essential's Terms of Service (the connect loop waits for that; the instance is Verity's sandbox). With real sign-ins (the nightly `verity.json` run), every target signs in through Mojang's session server with the one account at once. Mojang rate-limits that (429; Essential retries every 5 s), so the last targets connect minutes after the first. Tunables: `prometheus.verity.connectTimeout` (900 s; the replay profile uses 120), `.timeout` (other phases, 240 s), `.settle` (15 s), `.survive` (10 s), `.legacyRatio` (0.9), `.online` (`false` skips the checks that need Essential's servers, for local runs without an account).
  - The checks use `verity-api` (`ing.prometheusreengineer.verity:verity-api` from `maven.prometheusreengineer.ing`), which the shade plugin puts in the jar relocated to `studio.dreamys.prometheus.essential.shadow.verity`, since Forge 1.17+ and NeoForge refuse two jars with the same package; it's the only dependency that goes into the jar.
  - Locally: `java -jar verity-cli-*-all.jar run verity.json -o 1.8.9-forge,1.20.1-fabric` after `verity account login`. To replay instead:
    1. Run `verity run verity-record.json`, then take `recording.jsonl` from the report's `targets/*/attachments`.
    2. Run `mvn -B package -f tools/cm-replay/pom.xml && java -jar tools/cm-replay/target/cm-replay.jar recording.jsonl`.
    3. Run `verity run verity-replay.json -o ...`. CI fetches `lib/essential.mock.jar` from Essential's API (`ESSENTIAL_VERSION` in the workflow).
- Code targets Java 8: no `var`, `List.of`, records, etc.
- In-game testing is done by the user (Prism instances); report what was and wasn't verified.
