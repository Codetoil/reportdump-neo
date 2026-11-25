package io.codetoil.reportdump.neo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.level.storage.loot.Validatable;

/**
 * What the server ended up with after its data reload, including changes made in code (KubeJS
 * or KubeGroovy scripts, mods' reload listeners), re-encoded as data-pack JSON in loaded/data/: recipes, loot
 * tables, predicates, item modifiers, advancements.
 *
 * reports/loaded_data.json compares those ids with the data files: ids added in code, files that
 * didn't load (removed by scripts, conditions not met, parse errors) and elements that couldn't be
 * encoded back to JSON. It doesn't flag changed contents; KubeJS and KubeGroovy log their recipe
 * changes in logs/kubejs/server.log and logs/kubegroovy/server.log.
 */
final class LoadedData implements DataProvider {
    private final PackOutput output;
    private final PackOutput loadedOutput;
    private final ReloadableServerResources loaded;
    private final ResourceManager files;

    LoadedData(PackOutput output, ReloadableServerResources loaded, ResourceManager files) {
        this.output = output;
        this.loadedOutput = new PackOutput(output.getOutputFolder().resolve("loaded"));
        this.loaded = loaded;
        this.files = files;
    }

    @Override
    public String getName() {
        return "Loaded server data";
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        HolderLookup.Provider registries = loaded.fullRegistries().lookup();
        RegistryOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
        List<CompletableFuture<?>> writes = new ArrayList<>();
        JsonObject report = new JsonObject();

        Map<Identifier, Recipe<?>> recipes = new TreeMap<>();
        loaded.getRecipeManager().getRecipes().forEach(holder -> recipes.put(holder.id().identifier(), holder.value()));
        report.add(Registries.RECIPE.identifier().toString(), dump(cache, writes, ops, Registries.RECIPE, Recipe.CODEC, recipes));

        LootDataType.values().forEach(type -> report.add(type.registryKey().identifier().toString(), dumpLoot(cache, writes, ops, registries, type)));

        Map<Identifier, Advancement> advancements = new TreeMap<>();
        loaded.getAdvancements().getAllAdvancements().forEach(holder -> advancements.put(holder.id(), holder.value()));
        report.add(Registries.ADVANCEMENT.identifier().toString(), dump(cache, writes, ops, Registries.ADVANCEMENT, Advancement.CODEC, advancements));

        writes.add(DataProvider.saveStable(cache, report, output.getOutputFolder(PackOutput.Target.REPORTS).resolve("loaded_data.json")));
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    private <T extends Validatable> JsonObject dumpLoot(CachedOutput cache, List<CompletableFuture<?>> writes, RegistryOps<JsonElement> ops,
                                    HolderLookup.Provider registries, LootDataType<T> type) {
        Map<Identifier, T> elements = new TreeMap<>();
        registries.lookupOrThrow(type.registryKey()).listElements().forEach(holder -> elements.put(holder.key().identifier(), holder.value()));
        return dump(cache, writes, ops, type.registryKey(), type.codec(), elements);
    }

    /** Writes each element to loaded/data/<ns>/<registry dir>/<path>.json and returns its comparison with the files. */
    private <T> JsonObject dump(CachedOutput cache, List<CompletableFuture<?>> writes, RegistryOps<JsonElement> ops,
                                ResourceKey<? extends Registry<? extends T>> registry, Codec<T> codec, Map<Identifier, T> elements) {
        PackOutput.PathProvider paths = loadedOutput.createRegistryElementsPathProvider(registry);
        JsonObject encodeFailures = new JsonObject();
        elements.forEach((id, element) -> {
            try {
                DataResult<JsonElement> json = codec.encodeStart(ops, element);
                json.ifSuccess(j -> writes.add(DataProvider.saveStable(cache, j, paths.json(id))))
                        .ifError(error -> encodeFailures.addProperty(id.toString(), error.message()));
            } catch (RuntimeException e) {
                encodeFailures.addProperty(id.toString(), e.toString());
            }
        });

        FileToIdConverter converter = FileToIdConverter.registry(registry);
        Set<Identifier> fileIds = converter.listMatchingResources(files).keySet().stream().map(converter::fileToId).collect(Collectors.toCollection(TreeSet::new));
        JsonObject out = new JsonObject();
        out.addProperty("loaded", elements.size());
        out.addProperty("files", fileIds.size());
        out.add("added_in_code", ids(elements.keySet().stream().filter(id -> !fileIds.contains(id)).toList()));
        out.add("files_not_loaded", ids(fileIds.stream().filter(id -> !elements.containsKey(id)).toList()));
        out.add("encode_failures", encodeFailures);
        return out;
    }

    private static JsonArray ids(List<Identifier> ids) {
        JsonArray array = new JsonArray();
        ids.forEach(id -> array.add(id.toString()));
        return array;
    }
}
