package com.dash.client.client.mixin;

import com.dash.client.client.XRay;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class XRayLevelRendererMixin {
    @Inject(method = {"submitBlockEntities", "renderBlockDestroyAnimation"}, at = @At("HEAD"), cancellable = true)
    private void dash$hideBlockEffects(CallbackInfo ci) {
        if (XRay.isRendering()) {
            ci.cancel();
        }
    }
}
