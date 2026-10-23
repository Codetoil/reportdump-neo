package io.codetoil.reportdump.neo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;

/**
 * reports/data_registries.json: for every data-driven registry (worldgen, dimensions, enchantments,
 * jukebox songs, mod registries…), its entry ids and its tags with their resolved members.
 * reports/static_registry_tags.json: the resolved tags of the static registries (blocks, items…),
 * whose entries are in registries.json from RegistryDumpReport.
 */
final class DataRegistriesReport implements DataProvider {
    private final PackOutput output;
    private final FullRegistries registries;

    DataRegistriesReport(PackOutput output, FullRegistries registries) {
        this.output = output;
        this.registries = registries;
    }

    @Override
    public String getName() {
        return "Data-driven registries";
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        JsonObject dataDriven = new JsonObject();
        JsonObject staticTags = new JsonObject();
        registries.lookup().listRegistries()
                .sorted(Comparator.comparing(lookup -> lookup.key().identifier().toString()))
                .forEach(lookup -> {
                    String name = lookup.key().identifier().toString();
                    if (BuiltInRegistries.REGISTRY.containsKey(lookup.key().identifier())) {
                        JsonObject tags = tags(lookup);
                        if (!tags.isEmpty()) staticTags.add(name, tags);
                    } else {
                        dataDriven.add(name, describe(lookup));
                    }
                });
        Path reports = output.getOutputFolder(PackOutput.Target.REPORTS);
        return CompletableFuture.allOf(
                DataProvider.saveStable(cache, dataDriven, reports.resolve("data_registries.json")),
                DataProvider.saveStable(cache, staticTags, reports.resolve("static_registry_tags.json")));
    }

    private static <T> JsonObject describe(HolderLookup.RegistryLookup<T> lookup) {
        JsonArray entries = new JsonArray();
        lookup.listElementIds().map(key -> key.identifier().toString()).sorted().forEach(entries::add);
        JsonObject out = new JsonObject();
        out.addProperty("entry_count", entries.size());
        out.add("entries", entries);
        out.add("tags", tags(lookup));
        return out;
    }

    /** Every tag of a registry with its resolved members (nested tags expanded, removals applied). */
    private static <T> JsonObject tags(HolderLookup.RegistryLookup<T> lookup) {
        JsonObject tags = new JsonObject();
        lookup.listTags().sorted(Comparator.comparing(tag -> tag.key().location().toString())).forEach(tag -> tags.add("#" + tag.key().location(), members(tag)));
        return tags;
    }

    private static <T> JsonElement members(HolderSet.Named<T> tag) {
        JsonArray members = new JsonArray();
        try {
            tag.stream().map(Holder::getRegisteredName).sorted().forEach(members::add);
        } catch (IllegalStateException e) {
            return new JsonPrimitive("unbound: " + e.getMessage());
        }
        return members;
    }
}
