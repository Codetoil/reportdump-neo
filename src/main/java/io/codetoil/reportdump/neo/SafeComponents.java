package io.codetoil.reportdump.neo;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;

/**
 * Vanilla's RegistryComponentsReport (same reports/minecraft/components/... files), except that an
 * element whose default components can't be encoded is named in components_problems.json instead
 * of aborting the whole report.
 */
final class SafeComponents implements DataProvider {
    private final PackOutput output;
    private final CompletableFuture<HolderLookup.Provider> registries;

    SafeComponents(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        this.output = output;
        this.registries = registries;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        return registries.thenCompose(registries -> {
            RegistryOps<JsonElement> registryOps = registries.createSerializationContext(JsonOps.INSTANCE);
            List<CompletableFuture<?>> writes = new ArrayList<>();
            JsonObject problems = new JsonObject();
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(pendingComponents -> {
                PackOutput.PathProvider registryPathProvider = output.createRegistryComponentPathProvider(pendingComponents.key());
                pendingComponents.forEach((element, components) -> {
                    if (components.isEmpty()) {
                        return;
                    }
                    Identifier elementId = element.key().identifier();
                    try {
                        DataComponentPatch patch = DataComponentPatch.builder().set(components).build();
                        DataResult<JsonElement> encoded = DataComponentPatch.CODEC.encodeStart(registryOps, patch);
                        encoded.ifSuccess(json -> {
                            JsonObject root = new JsonObject();
                            root.add("components", json);
                            writes.add(DataProvider.saveStable(cache, root, registryPathProvider.json(elementId)));
                        }).ifError(error -> problems.addProperty(elementId.toString(), error.message()));
                    } catch (RuntimeException e) {
                        problems.addProperty(elementId.toString(), e.toString());
                    }
                });
            });
            writes.add(DataProvider.saveStable(cache, problems, output.getOutputFolder(PackOutput.Target.REPORTS).resolve("components_problems.json")));
            return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
        });
    }

    @Override
    public String getName() {
        return "Default Components (all elements)";
    }
}
