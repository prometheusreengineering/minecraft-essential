# Contributing

Want help contributing? Join the [Discord](https://discord.gg/BFDWmPfmXg)!

## Code Contributions

To maintain cross-loader compatibility, we use [`Essential#init`](src/main/java/studio/dreamys/prometheus/essential/mixin/gg/essential/MixinEssential.java) as our entrypoint in place of loader entrypoints. If Essential's container (the auto-updater) is being loaded on Fabric, it will load the gg.essential classes into classpath during Fabric's preLaunch entrypoint, so we load the Mixin json file there instead. The Fabric entrypoint should not touch the rest of the codebase.

Please use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) in your pull request titles.

Essential's codebase can be found [here](https://github.com/SparkUniverse/Essential-Mod).

### AI

The use of AI-generated code is allowed so long as
 - It is used alongside human-generated code (fully vibe-coded PRs are not allowed)
 - You are the one creating and discussing the PR. We do not want to talk to your agent.
 - You are able to explain the code your agent has written as if you wrote it.

## Cosmetic Contributions

Have a cosmetic that we don't have in [cosmetics.json](src/main/resources/cosmetics.json)? Send us a pull request!

Your local cosmetics.json file can be found at
 - Windows: in `.minecraft/prometheus/essential`
 - MacOS: in `~/Library/Application Support/prometheus/essential`
 - Linux: in `$XDG_DATA_HOME/prometheus/essential` (usually `~/.local/share/...`)

Please start your PR title with `cosmetics:` to indicate you are giving us new cosmetics.
