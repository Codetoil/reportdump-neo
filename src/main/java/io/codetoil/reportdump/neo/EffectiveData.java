package io.codetoil.reportdump.neo;

import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.neoforge.common.conditions.ConditionalOps;
import net.neoforged.neoforge.common.conditions.ICondition;
import org.jspecify.annotations.Nullable;

/**
 * Writes the server data the game would load from vanilla plus every mod jar, to data/:
 * <ul>
 * <li>JSON and other files: the copy from the highest-priority pack, as the loaders read it. If that
 * copy's {@code neoforge:conditions} fail, the game skips the id entirely (no fallback to lower
 * packs), so it goes to skipped_by_conditions/ instead.</li>
 * <li>Tags: merged across packs in load order ({@code replace} resets; {@code remove} entries that
 * name an id are applied, others such as {@code #tag} removals are kept). Tag files have no
 * conditions in 26.1.2. Resolved members of every tag are in data_registries.json.</li>
 * </ul>
 * reports/effective_data/: packs.json (pack order and counts), overrides.json (ids provided by more
 * than one pack, lowest priority first), conditions_failed.json (skipped ids and their pack).
 *
 * Not included: KubeJS virtual data and scripts, world data packs, biome/structure modifiers applied
 * in code.
 */
final class EffectiveData implements DataProvider {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final PackOutput output;
    private final ResourceManager resources;
    private final @Nullable DynamicOps<JsonElement> conditionOps;

    EffectiveData(PackOutput output, ResourceManager resources, @Nullable FullRegistries registries) {
        this.output = output;
        this.resources = resources;
        this.conditionOps = registries == null ? null
                : new ConditionalOps<>(registries.lookup().createSerializationContext(JsonOps.INSTANCE), registries.conditions());
    }

    @Override
    public String getName() {
        return "Effective server data";
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        try {
            dump(cache);
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void dump(CachedOutput cache) throws IOException {
        Path dataDir = output.getOutputFolder(PackOutput.Target.DATA_PACK);
        Path skippedDir = output.getOutputFolder().resolve("skipped_by_conditions");
        Path reportDir = output.getOutputFolder(PackOutput.Target.REPORTS).resolve("effective_data");

        Map<String, int[]> perPack = new LinkedHashMap<>(); // pack id -> {files provided, files won}
        resources.listPacks().forEach(p -> perPack.put(p.packId(), new int[2]));
        JsonObject overrides = new JsonObject();
        JsonObject failed = new JsonObject();
        int files = 0, mergedTags = 0, failedMerges = 0, conditionErrors = 0;

        for (String root : roots()) {
            var stacks = new TreeMap<>(resources.listResourceStacks(root, id -> true));
            for (var entry : stacks.entrySet()) {
                Identifier id = entry.getKey();
                List<Resource> stack = entry.getValue();
                Resource top = stack.getLast();
                byte[] bytes = null;
                boolean isTag = root.equals("tags") && id.getPath().endsWith(".json");
                if (isTag && stack.size() > 1) {
                    try {
                        bytes = mergeTag(stack);
                        mergedTags++;
                    } catch (Exception e) {
                        ReportDump.LOGGER.warn("Couldn't merge tag {}: {}", id, e.toString());
                        failedMerges++;
                    }
                }
                if (bytes == null) {
                    try (var in = top.open()) {
                        bytes = in.readAllBytes();
                    }
                }

                Path base = dataDir;
                if (!isTag && id.getPath().endsWith(".json") && conditionOps != null) {
                    String reason = null;
                    try {
                        if (!conditionsMet(bytes)) {
                            reason = "conditions not met";
                        }
                    } catch (RuntimeException e) {
                        // The game can't parse these either ("Couldn't parse data file"), so they don't load.
                        conditionErrors++;
                        reason = "conditions unreadable: " + e.getMessage();
                    }
                    if (reason != null) {
                        base = skippedDir;
                        JsonObject skipped = new JsonObject();
                        skipped.addProperty("pack", top.sourcePackId());
                        skipped.addProperty("reason", reason);
                        failed.add(id.toString(), skipped);
                    }
                }
                cache.writeIfNeeded(base.resolve(id.getNamespace()).resolve(id.getPath()), bytes, Hashing.sha1().hashBytes(bytes));
                files++;

                JsonArray sources = new JsonArray();
                for (Resource r : stack) {
                    sources.add(r.sourcePackId());
                    perPack.computeIfAbsent(r.sourcePackId(), k -> new int[2])[0]++;
                }
                perPack.get(top.sourcePackId())[1]++;
                if (stack.size() > 1) {
                    overrides.add(id.toString(), sources);
                }
            }
        }

        JsonArray packs = new JsonArray();
        perPack.forEach((pack, counts) -> {
            JsonObject o = new JsonObject();
            o.addProperty("pack", pack);
            o.addProperty("files", counts[0]);
            o.addProperty("winning_files", counts[1]);
            packs.add(o);
        });
        JsonObject summary = new JsonObject();
        summary.addProperty("note", "Packs are listed lowest priority first; the last pack providing a file wins (tags are merged).");
        summary.addProperty("files", files);
        summary.addProperty("ids_with_more_than_one_source", overrides.size());
        summary.addProperty("merged_tags", mergedTags);
        summary.addProperty("tag_merge_failures", failedMerges);
        summary.addProperty("conditions_evaluated", conditionOps != null);
        summary.addProperty("skipped_by_conditions", failed.size());
        summary.addProperty("condition_errors", conditionErrors);
        summary.add("packs", packs);
        write(cache, reportDir.resolve("packs.json"), summary);
        write(cache, reportDir.resolve("overrides.json"), overrides);
        write(cache, reportDir.resolve("conditions_failed.json"), failed);
    }

    /** Whether a JSON file loads: true unless it has neoforge:conditions that fail. */
    private boolean conditionsMet(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.contains(ConditionalOps.DEFAULT_CONDITIONS_KEY)) {
            return true;
        }
        JsonElement json = JsonParser.parseString(text);
        return !json.isJsonObject() || !json.getAsJsonObject().has(ConditionalOps.DEFAULT_CONDITIONS_KEY)
                || ICondition.conditionsMatched(conditionOps, json);
    }

    /** Merges one tag across its stack in load order, like TagLoader: replace=true drops lower packs' entries. */
    private static byte[] mergeTag(List<Resource> stack) throws IOException {
        Map<String, JsonElement> values = new LinkedHashMap<>();
        Map<String, JsonElement> unresolvedRemoves = new LinkedHashMap<>();
        for (Resource r : stack) {
            JsonObject tag;
            try (Reader reader = r.openAsReader()) {
                tag = JsonParser.parseReader(reader).getAsJsonObject();
            }
            if (tag.has("replace") && tag.get("replace").getAsBoolean()) {
                values.clear();
                unresolvedRemoves.clear();
            }
            if (tag.has("values")) {
                for (JsonElement v : tag.getAsJsonArray("values")) {
                    values.putIfAbsent(key(v), v);
                }
            }
            if (tag.has("remove")) {
                for (JsonElement v : tag.getAsJsonArray("remove")) {
                    if (values.remove(key(v)) == null) {
                        unresolvedRemoves.putIfAbsent(key(v), v);
                    }
                }
            }
        }
        JsonObject merged = new JsonObject();
        JsonArray valueArray = new JsonArray();
        values.values().forEach(valueArray::add);
        merged.add("values", valueArray);
        if (!unresolvedRemoves.isEmpty()) {
            JsonArray removeArray = new JsonArray();
            unresolvedRemoves.values().forEach(removeArray::add);
            merged.add("remove", removeArray);
        }
        return GSON.toJson(merged).getBytes(StandardCharsets.UTF_8);
    }

    private static String key(JsonElement v) {
        return v.isJsonObject() && v.getAsJsonObject().has("id") ? v.getAsJsonObject().get("id").getAsString() : v.getAsString();
    }

    /** Top-level data folders to walk, from -Dreportdump.rootsFile (comma-separated). */
    private static List<String> roots() throws IOException {
        String file = System.getProperty("reportdump.rootsFile");
        if (file == null) {
            return List.of("worldgen", "dimension", "dimension_type", "tags", "recipe", "loot_table", "advancement", "neoforge");
        }
        List<String> roots = new ArrayList<>();
        for (String r : Files.readString(Path.of(file)).trim().split(",")) {
            if (!r.isBlank()) roots.add(r.trim());
        }
        return roots;
    }

    private static void write(CachedOutput cache, Path path, JsonElement json) throws IOException {
        byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
        cache.writeIfNeeded(path, bytes, Hashing.sha1().hashBytes(bytes));
    }
}
