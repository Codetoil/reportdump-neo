package io.codetoil.reportdump.neo;

import dev.latvian.mods.kubejs.server.ServerScriptManager;
import dev.latvian.mods.kubejs.util.RegistryAccessContainer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * The only class that touches KubeJS; ReportDump uses it only when KubeJS is installed. It does what
 * KubeJS's own mixins do when a server starts, so the data KubeJS adds or changes is loaded here too.
 */
final class KubeJSCompat {
    private KubeJSCompat() {
    }

    /**
     * Like KubeJS's WorldLoaderPackConfigMixin: adds KubeJS's packs (kubejs/data, data generated and
     * registry entries added by server scripts) to the data packs and loads the server scripts. The
     * returned manager carries the script manager, which KubeJS's tag and recipe mixins look for.
     */
    static ResourceManager bindServerResources(ResourceManager data) {
        return ServerScriptManager.bindServerResources(data.listPacks().toList(), true, MultiPackResourceManager::new);
    }

    /** Like KubeJS's WorldLoaderMixin: the registries server scripts see while data loads. */
    static void setRegistryAccess(RegistryAccess.Frozen access) {
        RegistryAccessContainer.current = new RegistryAccessContainer(access);
    }
}
