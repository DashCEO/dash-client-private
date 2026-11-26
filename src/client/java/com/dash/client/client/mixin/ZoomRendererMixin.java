package com.dash.client.client.mixin;

import com.dash.client.client.Zoom;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
abstract class ZoomRendererMixin {
    @Inject(method = "getFov(Lnet/minecraft/client/Camera;FZ)F", at = @At("RETURN"), cancellable = true)
    private void dash$zoomWorld(Camera camera, float tickDelta, boolean useFovSetting, CallbackInfoReturnable<Float> cir) {
        if (useFovSetting) {
            cir.setReturnValue(Zoom.applyFov(Minecraft.getInstance(), cir.getReturnValueF()));
        }
    }
}
