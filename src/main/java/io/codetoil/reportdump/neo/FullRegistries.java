package io.codetoil.reportdump.neo;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.Util;
import net.minecraft.world.flag.FeatureFlags;
import net.neoforged.neoforge.common.conditions.ConditionContext;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.registries.DataPackRegistriesHooks;

/**
 * The registries a server would have before its first reload: static registries (with all mods),
 * their tags as pending tags, the data-driven registries (vanilla's plus every registry mods add
 * through NeoForge) and dimensions, loaded from the given data in the same order as WorldLoader.
 *
 * @param lookup     static (tags bound) + data-driven + dimension registries, for codecs and reports
 * @param access     the same registries as a RegistryAccess
 * @param conditions the context NeoForge evaluates {@code neoforge:conditions} with
 */
record FullRegistries(HolderLookup.Provider lookup, RegistryAccess.Frozen access, ICondition.IContext conditions) {
    static FullRegistries load(ResourceManager resources) {
        Executor executor = Util.backgroundExecutor();
        LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
        List<Registry.PendingTags<?>> staticTags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
        List<HolderLookup.RegistryLookup<?>> staticLookups = TagLoader.buildUpdatedLookups(layers.getAccessForLoading(RegistryLayer.WORLDGEN), staticTags);
        RegistryAccess.Frozen worldgen = RegistryDataLoader.load(resources, staticLookups, DataPackRegistriesHooks.getDataPackRegistries(), executor).join();
        List<HolderLookup.RegistryLookup<?>> withWorldgen = Stream.concat(staticLookups.stream(), worldgen.listRegistries()).toList();
        RegistryAccess.Frozen dimensions = RegistryDataLoader.load(resources, withWorldgen, RegistryDataLoader.DIMENSION_REGISTRIES, executor).join();

        RegistryAccess.Frozen access = layers.replaceFrom(RegistryLayer.WORLDGEN, worldgen, dimensions).compositeAccess();
        // Bind the static registries' tags, as the server does after its first data load
        // (ReloadableServerResources.updateComponentsAndStaticRegistryTags); until then they are unbound.
        staticTags.forEach(Registry.PendingTags::apply);
        return new FullRegistries(access, access, new ConditionContext(staticTags, access, access, FeatureFlags.DEFAULT_FLAGS));
    }
}
