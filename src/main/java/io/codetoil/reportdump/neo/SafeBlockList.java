package io.codetoil.reportdump.neo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BlockTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Vanilla's BlockListReport (same blocks.json format), except that a block whose type can't be
 * serialized (a mod that never registered its block codec in BlockTypes) is still listed, without
 * "definition", and named in blocks_problems.json instead of aborting the whole report.
 */
final class SafeBlockList implements DataProvider {
    private final PackOutput output;
    private final CompletableFuture<HolderLookup.Provider> registries;

    SafeBlockList(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        this.output = output;
        this.registries = registries;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        Path reports = output.getOutputFolder(PackOutput.Target.REPORTS);
        return registries.thenCompose(registries -> {
            JsonObject root = new JsonObject();
            JsonObject problems = new JsonObject();
            RegistryOps<JsonElement> registryOps = registries.createSerializationContext(JsonOps.INSTANCE);
            registries.lookupOrThrow(Registries.BLOCK).listElements().forEach(block -> {
                JsonObject entry = new JsonObject();
                StateDefinition<Block, BlockState> definition = block.value().getStateDefinition();
                if (!definition.getProperties().isEmpty()) {
                    JsonObject properties = new JsonObject();
                    for (Property<?> property : definition.getProperties()) {
                        JsonArray values = new JsonArray();
                        for (Comparable<?> value : property.getPossibleValues()) {
                            values.add(Util.getPropertyName(property, value));
                        }
                        properties.add(property.getName(), values);
                    }
                    entry.add("properties", properties);
                }

                JsonArray protocol = new JsonArray();
                for (BlockState state : definition.getPossibleStates()) {
                    JsonObject stateEntry = new JsonObject();
                    JsonObject properties = new JsonObject();
                    for (Property<?> property : definition.getProperties()) {
                        properties.addProperty(property.getName(), Util.getPropertyName(property, state.getValue(property)));
                    }
                    if (!properties.isEmpty()) {
                        stateEntry.add("properties", properties);
                    }
                    stateEntry.addProperty("id", Block.getId(state));
                    if (state == block.value().defaultBlockState()) {
                        stateEntry.addProperty("default", true);
                    }
                    protocol.add(stateEntry);
                }
                entry.add("states", protocol);

                String id = block.getRegisteredName();
                try {
                    DataResult<JsonElement> data = BlockTypes.CODEC.codec().encodeStart(registryOps, block.value());
                    data.ifSuccess(json -> entry.add("definition", json))
                            .ifError(error -> problems.addProperty(id, block.value().getClass().getName() + ": " + error.message()));
                } catch (RuntimeException e) {
                    problems.addProperty(id, block.value().getClass().getName() + ": " + e);
                }
                root.add(id, entry);
            });
            return CompletableFuture.allOf(
                    DataProvider.saveStable(cache, root, reports.resolve("blocks.json")),
                    DataProvider.saveStable(cache, problems, reports.resolve("blocks_problems.json")));
        });
    }

    @Override
    public String getName() {
        return "Block List (all blocks)";
    }
}
