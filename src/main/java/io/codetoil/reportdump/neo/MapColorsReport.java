package io.codetoil.reportdump.neo;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.util.Util;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.MapColor;

/**
 * The color each block shows on in-game maps ({@link MapColor#col}, before the map's brightness
 * shading), for rendering maps from region files. map_colors.json maps a block id to
 * {"color": "#rrggbb"} for its default state ("none" for {@link MapColor#NONE}, which maps look
 * through), plus "states" for the states whose color differs, keyed "name=value,..." with the
 * properties sorted by name. Blocks whose color can't be read are named in map_colors_problems.json.
 */
final class MapColorsReport implements DataProvider {
    private final PackOutput output;

    MapColorsReport(PackOutput output) {
        this.output = output;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        Path reports = output.getOutputFolder(PackOutput.Target.REPORTS);
        JsonObject root = new JsonObject();
        JsonObject problems = new JsonObject();
        BuiltInRegistries.BLOCK.listElements().forEach(block -> {
            String id = block.getRegisteredName();
            try {
                JsonElement defaultColor = color(block.value().defaultBlockState());
                JsonObject states = new JsonObject();
                for (BlockState state : block.value().getStateDefinition().getPossibleStates()) {
                    JsonElement color = color(state);
                    if (!color.equals(defaultColor)) {
                        states.add(key(state), color);
                    }
                }
                JsonObject entry = new JsonObject();
                entry.add("color", defaultColor);
                if (!states.isEmpty()) {
                    entry.add("states", states);
                }
                root.add(id, entry);
            } catch (RuntimeException e) {
                // A mod's getMapColor override may need a real level (block entity, neighbors).
                problems.addProperty(id, block.value().getClass().getName() + ": " + e);
            }
        });
        return CompletableFuture.allOf(
                DataProvider.saveStable(cache, root, reports.resolve("map_colors.json")),
                DataProvider.saveStable(cache, problems, reports.resolve("map_colors_problems.json")));
    }

    private static JsonElement color(BlockState state) {
        // Not JsonNull: the report writer drops null members.
        MapColor color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        return new JsonPrimitive(color == null || color == MapColor.NONE ? "none" : String.format("#%06x", color.col));
    }

    private static String key(BlockState state) {
        return state.getProperties().stream()
                .sorted(Comparator.comparing(Property::getName))
                .map(property -> property.getName() + "=" + Util.getPropertyName(property, state.getValue(property)))
                .collect(Collectors.joining(","));
    }

    @Override
    public String getName() {
        return "Map colors";
    }
}
