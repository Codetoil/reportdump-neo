package io.codetoil.reportdump.neo.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link PlatformWrapperMixin} for KubeGroovy, the Groovy fork of KubeJS: its PlatformWrapper has the
 * same check, which makes it skip its scripts in data runs. The target is named as a string because
 * KubeGroovy isn't a build dependency; ReportDumpMixinPlugin skips this mixin when it isn't installed.
 */
@Pseudo
@Mixin(targets = "dev.uncandango.kubegroovy.script.PlatformWrapper")
public abstract class KubeGroovyPlatformWrapperMixin {
    @Inject(method = "isGeneratingData", at = @At("HEAD"), cancellable = true)
    private static void reportdump$runScriptsInDataRuns(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
