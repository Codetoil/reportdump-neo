package io.codetoil.reportdump.neo.mixins;

import dev.latvian.mods.kubejs.script.PlatformWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * KubeJS skips its startup and server scripts during data generation (ScriptManager.load and
 * KubeJS's constructor check this). Reportdump wants what a server gets: script-registered items
 * and blocks, and script changes to tags, recipes and data. This also turns on KubeJS's console
 * log files and its background log-flushing thread, as on a server.
 */
@Mixin(PlatformWrapper.class)
public abstract class PlatformWrapperMixin {
    @Inject(method = "isGeneratingData", at = @At("HEAD"), cancellable = true)
    private static void reportdump$runScriptsInDataRuns(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
