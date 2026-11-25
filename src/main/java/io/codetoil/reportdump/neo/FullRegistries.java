package io.codetoil.reportdump.neo;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
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
 * @param layers     the registry layers, for {@link #loadServerResources}
 * @param staticTags the static registries' tags as loaded from the data
 */
record FullRegistries(HolderLookup.Provider lookup, RegistryAccess.Frozen access, ICondition.IContext conditions,
                      LayeredRegistryAccess<RegistryLayer> layers, List<Registry.PendingTags<?>> staticTags) {
    /**
     * @param loadingContext told which registries data is being loaded against, at the points where
     *                       KubeJS's (and KubeGroovy's) WorldLoaderMixin records them for its scripts
     */
    static FullRegistries load(ResourceManager resources, Consumer<RegistryAccess.Frozen> loadingContext) {
        Executor executor = Util.backgroundExecutor();
        LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
        List<Registry.PendingTags<?>> staticTags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
        // As in WorldLoader, both loads get the static tags: NeoForge evaluates neoforge:conditions in
        // data-driven registry files (tag_empty and the like) against them.
        RegistryAccess.Frozen worldgenLoadContext = layers.getAccessForLoading(RegistryLayer.WORLDGEN);
        loadingContext.accept(worldgenLoadContext);
        List<HolderLookup.RegistryLookup<?>> staticLookups = TagLoader.buildUpdatedLookups(worldgenLoadContext, staticTags);
        RegistryAccess.Frozen worldgen = RegistryDataLoader.load(resources, staticLookups, DataPackRegistriesHooks.getDataPackRegistries(), executor, staticTags).join();
        List<HolderLookup.RegistryLookup<?>> withWorldgen = Stream.concat(staticLookups.stream(), worldgen.listRegistries()).toList();
        RegistryAccess.Frozen dimensions = RegistryDataLoader.load(resources, withWorldgen, RegistryDataLoader.DIMENSION_REGISTRIES, executor, staticTags).join();

        layers = layers.replaceFrom(RegistryLayer.WORLDGEN, worldgen, dimensions);
        RegistryAccess.Frozen access = layers.compositeAccess();
        loadingContext.accept(access);
        // Bind the static registries' tags, as the server does after its first data load
        // (ReloadableServerResources.updateComponentsAndStaticRegistryTags); until then they are unbound.
        staticTags.forEach(Registry.PendingTags::apply);
        return new FullRegistries(access, access, new ConditionContext(staticTags, access, access, FeatureFlags.DEFAULT_FLAGS), layers, staticTags);
    }

    /**
     * The server's first data reload, as WorldLoader runs it after the registries: recipes, loot
     * tables, advancements, functions and every mod's reload listener (KubeJS's or KubeGroovy's recipe
     * events run here), then the static registries' tags and item components are applied. Settings are a
     * dedicated server's defaults.
     */
    ReloadableServerResources loadServerResources(ResourceManager resources) {
        ReloadableServerResources loaded = ReloadableServerResources.loadResources(resources, layers, staticTags, FeatureFlags.DEFAULT_FLAGS,
                Commands.CommandSelection.DEDICATED, LevelBasedPermissionSet.GAMEMASTER, Util.backgroundExecutor(), Runnable::run).join();
        loaded.updateComponentsAndStaticRegistryTags();
        return loaded;
    }
}
