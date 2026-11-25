# Reportdump Neo: notes (2026-10-23 15:00 EDT; updated 2026-11-25 09:30 UTC for All The Mods 11 0.15.0-beta)

Goal: Minecraft's data reports **with all of All The Mods 11 loaded**, plus the data the game
actually loads, to vibe-code datapacks for the pack. Every report succeeds.

## The pack

- `~/datapacks/allthemods11`: the pack's own server install (started with its `startserver.sh`). It
  used to live in a NeoForge MDK's `run/`; that MDK is now the blank `~/IdeaProjects/NeoForge-26.1.2-blank`.
  In a cloud session it is `claude-datapacks/allthemods11`; the `gradle.properties` defaults are relative to
  this project on Codetoil's machine, so pass `-Patm11_pack_dir=<pack>` and `-Patm11_datagen_dir=<pack>/datagen`.
- **All The Mods 11 0.15.0-beta** (server files `ServerFiles-0.15.0-beta.zip`, CurseForge file 9639412,
  2026-11-15): MC 26.1.2, NeoForge 26.1.2.**126**, data pack format **101.1**, 300 mod jars,
  **KubeGroovy 1.0.2** instead of KubeJS (see "Script mods").
- Before that (these notes up to 2026-10-24) the pack was CurseForge file 9187108: NeoForge 26.1.2.109, 280 mods,
  KubeJS 8.0.6.
  Compared with the mod list in that world's `level.dat` (308 mod ids), 0.15.0-beta drops `kubejs`, `rhino`,
  `theurgy_kubejs`, `portingdeadlibs`, `power_armor`, adds 25 mod ids (Twilight Forest, The Bumblezone,
  RFTools, XNet, Modern Dynamics, KubeGroovy, …) and changes 102 versions (Regions Unexplored 0.7.0+beta2 →
  beta4; Tectonic 3.0.28, Terralith 2.6.1 and lithostitched 1.8.1 unchanged). Modern Dynamics, Occulted Jars and
  Logistics Networks need NeoForge ≥ 26.1.2.126.
- This project builds against NeoForge 26.1.2.**126**, the pack's build (it was .122 while the pack ran .109);
  `mods.toml` accepts any 26.1.2 build.
- Decompiled Minecraft+NeoForge sources: `build/moddev/artifacts/minecraft-patched-26.1.2.126-sources.jar`;
  NeoForge's own in `~/.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/26.1.2.126/*/neoforge-26.1.2.126-sources.jar`.

## Running it

Two data runs (both `serverData()`, `--mod reportdump`, `-Dreportdump.rootsFile=roots.txt`, the data
folder names to walk: 140, every `data/<ns>/<folder>` the pack, vanilla and KubeGroovy's folders have;
0.15.0-beta added 15, e.g. `kubegroovy`, `twilight`, `museumexhibits`, the Bumblezone's `bz_*`, `database`):
- `./gradlew runData`: local, just this mod and NeoForge (`-Pwith_kubejs` adds KubeJS 8.0.7), game folder
  `run/`, output `run/generated`. 154 registries, 1,168 blocks, 100 commands (102 with KubeJS) (2026-11-25).
- `./gradlew runDataATM11`: the whole All The Mods 11 pack, 6 GB heap max; about 30 s on Codetoil's machine
  with the old pack, 2 min 10 s with 0.15.0-beta in a 4-core cloud container (build included).
  1. `prepareDataATM11` fills the game folder `atm11_datagen_dir` (gradle.properties, default
     `../../datapacks/allthemods11/datagen`, i.e. `datagen/` inside the pack): links every jar from
     `atm11_pack_dir/mods`, and replaces `config`, `defaultconfigs`, `kubejs`, `kubegroovy` and `local`
     (the script mod's dev settings) with fresh copies; a folder the pack doesn't have is removed from the
     game folder (0.15.0-beta has no `kubejs`, and `defaultconfigs` only appears once the server has started).
     Apart from `datagen/` the pack folder is only read. The run's game folder is `datagen/`, so its logs go
     to `datagen/logs` and never rotate a running server's `logs/latest.log`.
     The pack doesn't need this mod's jar in its `mods/`; with it there, the jar would be linked
     in next to the dev build of the same mod.
  2. Output in `<datagen>/generated`.

`mods.toml` is generated from `src/main/templates` with the values in `gradle.properties`.

**Dev vs production:** both runs are dev environments. With All The Mods 11 (runDataATM11) some mods add
test content that players never see. 0.15.0-beta (2026-11-25): a production run in `test-prod/` (gitignored:
symlinks to the pack's `libraries/` and mod jars, the built jar in `mods/`, copies of `config/`,
`kubegroovy/`, `local/`; `libraries/net/neoforged/neoforge/26.1.2.126/unix_args.txt` with main class
`net.neoforged.fml.startup.DataServer` instead of `…Server` as `data_args.txt`; then
`java -Xmx6G -Dreportdump.rootsFile=../roots.txt @data_args.txt --mod reportdump --output generated`) gave
41,462 blocks / 48,576 items / 247 commands / 42,369 recipes / 8,642 skipped files instead of
41,473 / 48,590 / 251 / 42,413 / 8,592. Dev-only: 5 Titanium test blocks, 6 Generator Galore blocks, commands
`bumblezone_cooldown`, `bumblezone_debug_spawn_laser`, `ftbchunks_dev`, `newFamily`. With the old pack
(NeoForge .109, KubeJS 8.0.6) it was 31,833 blocks / 233 commands / 4,195 skipped files instead of
31,844 / 236 / 4,141 (also FancyTabSections placeholder items and the `chisel` command; Mystical
Agriculture and Generator Galore load 54 more files in dev). Dev mode is fine for this project; there is
no production-mode task.

## Script mods: KubeJS and KubeGroovy

All The Mods 11 0.15.0-beta replaced KubeJS with **KubeGroovy** (`dev.uncandango.kubegroovy`, by Uncandango and
latvian.dev): a fork of KubeJS that runs Groovy scripts from `kubegroovy/` (`startup_scripts`,
`server_scripts`, `data`, `dynamic_data`, the last with worldgen changes such as
`atm/neoforge/biome_modifier/remove_lava_springs`). Its `mods.toml` declares `kubejs` **incompatible**, so a
game with both doesn't start. Reportdump handles both the same way (`ReportDump.ScriptMod`); at most one is
installed.

- **KubeJS**: optional (`mods.toml`: `[26.1.2-8,)`, which accepts the old pack's 8.0.6), built against
  `kubejs_version` (26.1.2-8.0.7) as `compileOnly` only. Runs no longer load it: it used to be
  `localRuntime` + `interfaceInjectionData`, so it was in every run (interface injection bakes KubeJS's
  interfaces into the Minecraft jar the runs use; a run without KubeJS failed with
  `NoClassDefFoundError: dev/latvian/mods/kubejs/core/ComponentKJS`), and with KubeGroovy in the pack
  runDataATM11 wouldn't start. The code compiles without interface injection. `-Pwith_kubejs` puts KubeJS
  back on the runs' classpath (`localRuntime`) to test `KubeJSCompat` with runData: its example scripts
  run and it adds its commands `kjs` and `kubejs` (2026-11-25). runDataATM11 uses the pack's own script mod,
  so with a KubeJS pack it should load the pack's KubeJS (untested; FML used to prefer the newer 8.0.7 from
  the classpath).
- **KubeGroovy**: not published to any Maven repository (maven.latvian.dev has no such artifact; its
  CurseForge page only describes building from source), so `KubeGroovyCompat` reaches its entry points with
  method handles (`server.ServerScriptManager.bindServerResources`, `util.RegistryAccessContainer` and its
  static `current`) and `KubeGroovyPlatformWrapperMixin` targets `script.PlatformWrapper` by name (`@Pseudo`).
  `mods.toml`: optional, `[1,)`. Its `WorldLoaderPackConfigMixin` and `WorldLoaderMixin` are KubeJS's (same
  calls, same `true` flag, same two points), so the compat code mirrors `KubeJSCompat`.
- Outside Gradle the built jar needs neither. With the old pack: alone on NeoForge 26.1.2.109 it loaded and
  wrote its reports (154 registries, vanilla + NeoForge data; tested 2026-10-23 13:07). With 0.15.0-beta in
  a production install (see "Dev vs production") it loads KubeGroovy's scripts and writes every report.

## The mod (`src/main/java/io/codetoil/reportdump/neo`)

- `ReportDump`: loads common configs (data runs skip them in `CommonModLoader.begin`, and some load
  conditions read configs, e.g. Waystones'), then the registries, then adds the reports.
- `FullRegistries`: registries as `WorldLoader` loads them: static tags → data-driven registries
  (`DataPackRegistriesHooks.getDataPackRegistries()`, incl. modded) → dimensions; then binds static tags.
  Both `RegistryDataLoader.load` calls get the static tags, as in `WorldLoader`: NeoForge evaluates
  `neoforge:conditions` in data-driven registry files (`tag_empty` and the like) against them. (Until
  2026-11-25 it used the 4-argument overload, deprecated in .126, which passes no tags.)
- `SafeBlockList`, `SafeComponents`: vanilla's block and item-component reports with per-entry guards.
- `MapColorsReport`: each block state's in-game map color, for map renderers (see below).
- `DataRegistriesReport`, `EffectiveData`: see below. The other reports are vanilla's.
- `KubeJSCompat`, `KubeGroovyCompat`, `mixins/`: see "Script mods" and the section after "NeoForge quirks".

## Output (`<datagen>/generated`, 1.6 GB)

| File | Content (0.15.0-beta, 2026-11-25 runDataATM11; old pack's numbers in parentheses) |
|---|---|
| `reports/registries.json` | 230 (224) static registries (95 vanilla); 48,590 (38k) items, 41,473 (31,844) blocks, 945 (799) entity types |
| `reports/blocks.json` | all block states (vanilla part identical to the vanilla report); `blocks_problems.json`: 3,298 blocks without `definition` (mod never registered a block codec) |
| `reports/map_colors.json` | `{"<block>": {"color": "#rrggbb" or "none", "states": {"axis=x": …}}}`: `MapColor.col` of the default state, plus states that differ (properties sorted by name). 41,473 (31,851) blocks, 5,757 (5,645) `none` (maps look through them; also modded blocks that never set a map color, e.g. Mystical Agriculture and Allthemodium ores), 498 (483) with state colors; `map_colors_problems.json` lists blocks whose `getMapColor` throws (none) |
| `reports/<ns>/components/item/*.json` | default item components; `components_problems.json`: 42 (41) items with invalid defaults |
| `reports/commands.json` | 251 (236) top-level commands |
| `reports/data_registries.json` | 131 (119) data-driven registries: ids + resolved tags (319 (288) biomes, 2,342 (2,150) placed features, 306 (252) structures, 430 (415) biome modifiers, 16 (14) dimension entries: Twilight Forest and the Bumblezone are new) |
| `reports/static_registry_tags.json` | 8,831 (7,596) resolved tags of static registries (blocks, items, …) |
| `data/` | 146,789 (128,292) effective files (top pack wins; tags merged in load order) |
| `skipped_by_conditions/` | 8,592 files the game skips (conditions not met, or unreadable like Mystical Agriculture's `neoforge:false`) |
| `reports/effective_data/` | `packs.json` (334 (314) packs in order), `overrides.json` (3,121 (2,730) ids), `conditions_failed.json` |

Findings for datapacks:
- `#minecraft:is_overworld` has 222 biomes (Terralith 90, Regions Unexplored 73, vanilla 54, Thaumaturge 4,
  Integrated Dynamics 1); the old pack also had 222.
- Terralith's `dimension/overworld.json` is skipped by its conditions; the overworld comes from the world
  preset plus the overridden `noise_settings`.

## NeoForge quirks (26.1.2.109, still in the .126 sources)

- `--mod minecraft --mod <other>`: `RejectedExecutionException`; `Main` calls `Util.shutdownExecutors()`
  in the vanilla branch, then `DatagenModLoader.begin` needs the executor.
- `--reports` with mods only reaches mods that add providers; hence this mod.
- Data runs skip config loading and never reach common setup or load-complete.

## Script mods and the server data reload (2026-10-23 14:14, KubeJS; 2026-11-25, KubeGroovy)

With KubeJS or KubeGroovy installed the data is read the way such a server reads it:
- `PlatformWrapperMixin` (and `KubeGroovyPlatformWrapperMixin`) make `PlatformWrapper.isGeneratingData()`
  return false. The mods otherwise skip their startup and server scripts in data runs ("Skipping KubeJS
  script loading (DataGen)"). The mixin config's plugin (`ReportDumpMixinPlugin`) skips mixins whose target
  class is missing, so both stay optional.
- `KubeJSCompat.bindServerResources` = KubeJS's `WorldLoaderPackConfigMixin`: adds its 6 packs (kubejs/data,
  script-generated data, script-registered registry entries) and loads the server scripts. KubeJS's tag
  and recipe mixins only act on a resource manager made this way. `KubeGroovyCompat` does the same for
  KubeGroovy.
- `FullRegistries` loads registries from it (setting the mod's `RegistryAccessContainer.current` like its
  `WorldLoaderMixin`), then `loadServerResources` runs `ReloadableServerResources.loadResources` +
  `updateComponentsAndStaticRegistryTags`, as `WorldLoader` does. The recipe events run there.
- `LoadedData` writes what that reload produced to `loaded/data/` (recipes, loot tables, predicates, item
  modifiers, advancements, re-encoded) and `reports/loaded_data.json` (ids added in code, files not loaded,
  encode failures).

Results in runDataATM11 with 0.15.0-beta (KubeGroovy, 2026-11-25):
- KubeGroovy logs 21/21 startup and 111/111 server scripts with 0 errors; the other 4 and 47 `.groovy` files
  have a `// requires <mod>` line for mods the pack doesn't have (Mekanism, Ars Nouveau, Create, …) and are
  skipped ("Mod … is not loaded"). "Added 623 recipes, removed 134 recipes, modified 9 recipes, with 0 failed
  recipes" (`datagen/logs/kubegroovy/server.log`). 4 errors: Neo Vitae alchemy table recipes in
  `crafting_on_a_stick.groovy` need the item tag `c:dusts/hellforged`, which no loaded pack defines (Neo Vitae's
  jar has no such file; maybe the world-level `neovitae_materials` pack, which this run doesn't load, adds it).
- Registries have 92 `kubegroovy:` items, 2 blocks, 6 fluids and 3 fluid types. Packs: 334, including
  `mod/kubegroovy` and KubeGroovy's 6 (Virtual `[Before Mods]`, `[Internal]`, `[Registries]`, `[After Mods]`
  with 1,225 files, `[Last]`, and the File Resource Pack with 13); the last pack is `[Last]`.
- Reload: 42,413 recipes (1,166 not from files, 7,136 files not loaded), 41,557 loot tables, 2,004
  advancements. The pack's Utilitarian config disables recipe advancements, hence 25,904 unloaded
  `advancement/recipes/` files. 312 Productive Bees recipes load but can't be re-encoded (their codec
  throws `NullPointerException` on `beeType`), plus 1 `productivetrees` and 1 `sgearmetalworks` recipe.

The same with the old pack (KubeJS, 2026-10-23), compared with the run before KubeJS support:
- KubeJS logs 17/17 startup and 151/151 server scripts, and "Added 652 recipes, removed 137 recipes,
  modified 7 recipes" (the same as the pack's own server log).
- Registries gained 101 `kubejs:` items, 7 blocks, 6 fluids and 3 fluid types. 130 static tags changed;
  biome, feature and biome-modifier registries changed. Packs 308 → 314; data files 127,232 → 128,292.
- Reload: 37,116 recipes (1,145 not from files, 3,693 files not loaded), 31,790 loot tables, 1,763
  advancements (22,878 unloaded `advancement/recipes/` files). 377 Productive Bees recipes couldn't be re-encoded.

Still not covered: world-level packs (`neovitae_materials`, T&T waystones patch, potionsmaster recipes),
NeoForge global loot modifiers and data maps as loaded, functions.
