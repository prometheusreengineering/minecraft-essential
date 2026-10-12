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
- No test suite. For logic in `serial/`, a throwaway harness on the classpath with the mock jar plus the nested `kotlin-stdlib`, `kotlinx-serialization-*` and `elementa` jars can call real Essential model classes. Set `PROMETHEUS_FOLDER` to a scratch dir so files don't land in the real config.
- Code targets Java 8: no `var`, `List.of`, records, etc.
- In-game testing is done by the user (Prism instances); report what was and wasn't verified.
