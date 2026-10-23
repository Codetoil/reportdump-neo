package io.codetoil.reportdump.neo;

import java.util.concurrent.CompletableFuture;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.info.BiomeParametersDumpReport;
import net.minecraft.data.info.CommandsReport;
import net.minecraft.data.info.DatapackStructureReport;
import net.minecraft.data.info.PacketReport;
import net.minecraft.data.info.RegistryDumpReport;
import net.minecraft.server.jsonrpc.dataprovider.JsonRpcApiSchema;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.bus.api.IEventBus;
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
 * server data (see {@link EffectiveData}).
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
        // The generator's own lookup only has vanilla's data-driven registries; load the modded ones.
        FullRegistries full = null;
        try {
            full = FullRegistries.load(data);
            LOGGER.info("Loaded {} registries from all mods' data", full.access().listRegistryKeys().count());
        } catch (RuntimeException e) {
            LOGGER.error("Couldn't load the data-driven registries; falling back to vanilla's", e);
        }
        CompletableFuture<HolderLookup.Provider> lookup = full != null ? CompletableFuture.completedFuture(full.lookup()) : event.getLookupProvider();

        add(event, new RegistryDumpReport(out));
        add(event, new SafeBlockList(out, lookup));
        add(event, new SafeComponents(out, lookup));
        add(event, new CommandsReport(out, lookup));
        add(event, new BiomeParametersDumpReport(out, lookup));
        add(event, new DatapackStructureReport(out));
        add(event, new PacketReport(out));
        add(event, new JsonRpcApiSchema(out));
        if (full != null) {
            add(event, new DataRegistriesReport(out, full));
        }
        add(event, new EffectiveData(out, data, full));
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
