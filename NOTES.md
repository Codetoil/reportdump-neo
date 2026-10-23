# Reportdump Neo: notes (2026-10-23 12:50 EDT)

Goal: Minecraft's data reports **with all of All The Mods 11 loaded**, plus the data the game
actually loads, to vibe-code datapacks for the pack. Every report succeeds.

## The pack

- `~/IdeaProjects/AllTheMods11InDev/run` (inside a NeoForge 26.1.2 ModDevGradle MDK).
- MC 26.1.2, NeoForge 26.1.2.**109**, data pack format **101.1**, 280 mods, KubeJS 8.0.6.
- This project builds against NeoForge 26.1.2.**122** (kept on purpose); `mods.toml` accepts any 26.1.2 build.
- Decompiled Minecraft+NeoForge sources: `build/moddev/artifacts/minecraft-patched-26.1.2.122-sources.jar`;
  NeoForge's own in `~/.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/26.1.2.122/*/neoforge-26.1.2.122-sources.jar`.

## Running it

Two data runs (both `serverData()`, `--mod reportdump`, `-Dreportdump.rootsFile=roots.txt`, the data
folder names to walk):
- `./gradlew runData`: local, just this mod and its runtime dependencies (NeoForge, KubeJS), game folder
  `run/`, output `run/generated`. About 5 s; 154 registries, 1,168 blocks, 102 commands (13:18 run).
- `./gradlew runDataATM11`: the whole All The Mods 11 pack, about 30 s, 6 GB heap max.
  1. `prepareDataATM11` fills the game folder `atm11_datagen_dir` (gradle.properties, default
     `../AllTheMods11InDev/datagen`, git-ignored there): links every jar from `atm11_pack_dir/mods`, and
     replaces `config`, `defaultconfigs`, `kubejs` with fresh copies. The pack folder is only read.
  2. Output in `<datagen>/generated`.

`mods.toml` is generated from `src/main/templates` with the values in `gradle.properties`.

KubeJS is an optional dependency, built against the latest (`kubejs_version`, 26.1.2-8.0.7):
- `mods.toml`: `type = "optional"`, `versionRange = "[26.1.2-8,)"`. An installed optional dependency must
  be in range; this accepts the pack's 8.0.6.
- Gradle: `compileOnly` + `localRuntime` + `interfaceInjectionData`. Not published (the POM has no
  dependencies), but on every run's classpath: MDG bakes the injected interfaces into the Minecraft jar
  that runs use too, so a run without KubeJS fails with
  `NoClassDefFoundError: dev/latvian/mods/kubejs/core/ComponentKJS` (from `MutableComponent`).
- Both runs load KubeJS 8.0.7; in runDataATM11 FML prefers it over the pack's 8.0.6.
- Outside Gradle the built jar needs no KubeJS: alone on NeoForge 26.1.2.109 it loads and writes its
  reports (154 registries, vanilla + NeoForge data; tested 13:07).

**Dev vs production:** both runs are dev environments. With All The Mods 11 (runDataATM11) some mods add
test content that players never see: Titanium test blocks, Generator Galore 64x generators,
FancyTabSections placeholder items, commands `chisel`, `ftbchunks_dev`, `newFamily`; Mystical
Agriculture and Generator Galore then load 54 more files. A production run (the pack's own NeoForge
.109 and KubeJS 8.0.6, `net.neoforged.fml.startup.DataServer`, the built jar in its mods) gave
31,833 blocks / 233 commands / 4,195 skipped files instead of 31,844 / 236 / 4,141; everything else
was identical. Dev mode is fine for this project; there is no production-mode task.

## The mod (`src/main/java/io/codetoil/reportdump/neo`)

- `ReportDump`: loads common configs (data runs skip them in `CommonModLoader.begin`, and some load
  conditions read configs, e.g. Waystones'), then the registries, then adds the reports.
- `FullRegistries`: registries as `WorldLoader` loads them: static tags → data-driven registries
  (`DataPackRegistriesHooks.getDataPackRegistries()`, incl. modded) → dimensions; then binds static tags.
- `SafeBlockList`, `SafeComponents`: vanilla's block and item-component reports with per-entry guards.
- `DataRegistriesReport`, `EffectiveData`: see below. The other reports are vanilla's.

## Output (`<datagen>/generated`, ~1.1 GB)

| File | Content (12:46 Gradle run) |
|---|---|
| `reports/registries.json` | 224 static registries (95 vanilla); 38k items, 31,844 blocks, 799 entity types |
| `reports/blocks.json` | all block states (vanilla part identical to the vanilla report); `blocks_problems.json`: blocks without `definition` (mod never registered a block codec) |
| `reports/<ns>/components/item/*.json` | default item components; `components_problems.json`: 41 items with invalid defaults |
| `reports/commands.json` | 236 top-level commands |
| `reports/data_registries.json` | 119 data-driven registries: ids + resolved tags (288 biomes, 2,150 placed features, 252 structures, 415 biome modifiers, 14 dimension entries) |
| `reports/static_registry_tags.json` | 7,596 resolved tags of static registries (blocks, items, …) |
| `data/` | 127,232 effective files (top pack wins; tags merged in load order) |
| `skipped_by_conditions/` | files the game skips (conditions not met, or unreadable like Mystical Agriculture's `neoforge:false`) |
| `reports/effective_data/` | `packs.json` (308 packs in order), `overrides.json` (2,730 ids), `conditions_failed.json` |

Findings for datapacks:
- `#minecraft:is_overworld` has 222 biomes (Terralith 90, Regions Unexplored 73, vanilla 54).
- Terralith's `dimension/overworld.json` is skipped by its conditions; the overworld comes from the world
  preset plus the overridden `noise_settings`.

## NeoForge quirks (26.1.2.109, still in .122 sources)

- `--mod minecraft --mod <other>`: `RejectedExecutionException`; `Main` calls `Util.shutdownExecutors()`
  in the vanilla branch, then `DatagenModLoader.begin` needs the executor.
- `--reports` with mods only reaches mods that add providers; hence this mod.
- Data runs skip config loading and never reach common setup or load-complete.

## Not covered yet

KubeJS's runtime changes (server scripts: 652 recipes added and 137 removed at server start; tag and loot events;
its virtual data packs), world-level packs (`neovitae_materials`, T&T waystones patch, potionsmaster
recipes), biome/structure modifiers applied in code. KubeJS 8.0.6 hooks in through mixins on
`MultiPackResourceManager`, `ReloadableServerResources`, `RecipeManager`, `TagLoader` and `WorldLoader`;
running `ReloadableServerResources.loadResources` (what `WorldLoader` does next) is the likely way in.
