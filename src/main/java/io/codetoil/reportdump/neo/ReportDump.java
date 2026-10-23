package io.codetoil.reportdump.neo;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.info.BiomeParametersDumpReport;
import net.minecraft.data.info.CommandsReport;
import net.minecraft.data.info.DatapackStructureReport;
import net.minecraft.data.info.PacketReport;
import net.minecraft.data.info.RegistryDumpReport;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.jsonrpc.dataprovider.JsonRpcApiSchema;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs Minecraft's own data reports with every installed mod loaded, so the registries, block
 * states, item components and command tree include modded content. Also dumps the effective
 * server data files (see {@link EffectiveData}) and what the server's data reload made of them
 * (see {@link LoadedData}). With KubeJS installed, its packs and server scripts take part.
 *
 * Run with {@code ./gradlew runData} (see build.gradle), or in a production pack through the
 * {@code net.neoforged.fml.startup.DataServer} entry point with {@code --mod reportdump --output <dir>}.
 */
@Mod("reportdump")
public final class ReportDump {
    static final Logger LOGGER = LoggerFactory.getLogger("reportdump");

    public ReportDump(IEventBus modBus) {
        modBus.addListener(GatherDataEvent.Server.class, ReportDump::gather);
        modBus.addListener(GatherDataEvent.Client.class, ReportDump::gather);
    }

    private static void gather(GatherDataEvent event) {
        PackOutput out = event.getGenerator().getPackOutput();
        ResourceManager data = event.getResourceManager(PackType.SERVER_DATA);
        // Data runs skip config loading (CommonModLoader.begin), but load conditions such as
        // Waystones' read common configs; load them as a server does before reading data.
        try {
            ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.COMMON, FMLPaths.CONFIGDIR.get());
        } catch (RuntimeException e) {
            LOGGER.error("Couldn't load common configs", e);
        }
        // With KubeJS, read the data the way a KubeJS server does: its packs added and its server
        // scripts loaded, so script changes to tags, registries and recipes take effect below.
        boolean kubejs = ModList.get().isLoaded("kubejs");
        ResourceManager resources = data;
        if (kubejs) {
            try {
                resources = KubeJSCompat.bindServerResources(data);
                LOGGER.info("Added KubeJS's data packs and loaded its server scripts");
            } catch (RuntimeException e) {
                LOGGER.error("Couldn't load KubeJS's server data; KubeJS changes won't be included", e);
            }
        }
        Consumer<RegistryAccess.Frozen> loadingContext = kubejs ? KubeJSCompat::setRegistryAccess : access -> {};

        // The generator's own lookup only has vanilla's data-driven registries; load the modded ones.
        FullRegistries full = null;
        try {
            full = FullRegistries.load(resources, loadingContext);
            LOGGER.info("Loaded {} registries from all mods' data", full.access().listRegistryKeys().count());
        } catch (RuntimeException e) {
            LOGGER.error("Couldn't load the data-driven registries; falling back to vanilla's", e);
        }
        CompletableFuture<HolderLookup.Provider> lookup = full != null ? CompletableFuture.completedFuture(full.lookup()) : event.getLookupProvider();

        // Then the server's data reload (recipes, loot, advancements; KubeJS recipe events). It also
        // applies the static tags and item components, so the reports below see them as a server would.
        ReloadableServerResources loaded = null;
        if (full != null) {
            try {
                loaded = full.loadServerResources(resources);
                LOGGER.info("Loaded server resources: {} recipes, {} advancements",
                        loaded.getRecipeManager().getRecipes().size(), loaded.getAdvancements().getAllAdvancements().size());
            } catch (RuntimeException e) {
                LOGGER.error("Couldn't load the server resources; loaded/ and loaded_data.json won't be written", e);
            }
        }

        add(event, new RegistryDumpReport(out));
        add(event, new SafeBlockList(out, lookup));
        add(event, new MapColorsReport(out));
        add(event, new SafeComponents(out, lookup));
        add(event, new CommandsReport(out, lookup));
        add(event, new BiomeParametersDumpReport(out, lookup));
        add(event, new DatapackStructureReport(out));
        add(event, new PacketReport(out));
        add(event, new JsonRpcApiSchema(out));
        if (full != null) {
            add(event, new DataRegistriesReport(out, full));
        }
        if (loaded != null) {
            add(event, new LoadedData(out, loaded, resources));
        }
        add(event, new EffectiveData(out, resources, full));
    }

    /** Adds a provider whose failure is logged instead of aborting the other reports. */
    private static void add(GatherDataEvent event, DataProvider provider) {
        event.addProvider(new Guarded(provider));
    }

    private record Guarded(DataProvider inner) implements DataProvider {
        @Override
        public CompletableFuture<?> run(CachedOutput output) {
            try {
                return inner.run(output).exceptionally(e -> {
                    LOGGER.error("Report {} failed", inner.getName(), e);
                    return null;
                });
            } catch (Throwable e) {
                LOGGER.error("Report {} failed", inner.getName(), e);
                return CompletableFuture.completedFuture(null);
            }
        }

        @Override
        public String getName() {
            return inner.getName();
        }
    }
}
