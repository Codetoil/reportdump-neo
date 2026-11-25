package io.codetoil.reportdump.neo;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.function.BiFunction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * {@link KubeJSCompat} for KubeGroovy, the fork of KubeJS that runs Groovy scripts (All The Mods 11
 * uses it from 0.15.0-beta on). It has KubeJS's entry points under {@code dev.uncandango.kubegroovy}
 * and declares itself incompatible with KubeJS, so at most one of the two is installed. KubeGroovy
 * isn't published to a Maven repository, so this reaches it through method handles instead of a
 * compile dependency; ReportDump uses this class only when KubeGroovy is installed.
 */
final class KubeGroovyCompat {
    private static final MethodHandle BIND_SERVER_RESOURCES;
    private static final MethodHandle NEW_REGISTRY_ACCESS_CONTAINER;
    private static final MethodHandle SET_CURRENT_REGISTRY_ACCESS;

    static {
        try {
            ClassLoader loader = KubeGroovyCompat.class.getClassLoader();
            Class<?> serverScripts = Class.forName("dev.uncandango.kubegroovy.server.ServerScriptManager", true, loader);
            Class<?> container = Class.forName("dev.uncandango.kubegroovy.util.RegistryAccessContainer", true, loader);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            BIND_SERVER_RESOURCES = lookup.findStatic(serverScripts, "bindServerResources",
                    MethodType.methodType(MultiPackResourceManager.class, List.class, boolean.class, BiFunction.class));
            NEW_REGISTRY_ACCESS_CONTAINER = lookup.findConstructor(container, MethodType.methodType(void.class, RegistryAccess.Frozen.class));
            SET_CURRENT_REGISTRY_ACCESS = lookup.findStaticSetter(container, "current", container);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private KubeGroovyCompat() {
    }

    /**
     * Like KubeGroovy's WorldLoaderPackConfigMixin (the same as KubeJS's): adds its packs (kubegroovy/data,
     * kubegroovy/dynamic_data, data generated and registry entries added by server scripts) to the data
     * packs and loads the server scripts. The returned manager carries the script manager, which
     * KubeGroovy's tag and recipe mixins look for.
     */
    static ResourceManager bindServerResources(ResourceManager data) {
        BiFunction<PackType, List<PackResources>, MultiPackResourceManager> create = MultiPackResourceManager::new;
        try {
            return (MultiPackResourceManager) BIND_SERVER_RESOURCES.invoke(data.listPacks().toList(), true, create);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    /** Like KubeGroovy's WorldLoaderMixin: the registries server scripts see while data loads. */
    static void setRegistryAccess(RegistryAccess.Frozen access) {
        try {
            SET_CURRENT_REGISTRY_ACCESS.invoke(NEW_REGISTRY_ACCESS_CONTAINER.invoke(access));
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    private static RuntimeException rethrow(Throwable e) {
        if (e instanceof Error error) {
            throw error;
        }
        return e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
    }
}
