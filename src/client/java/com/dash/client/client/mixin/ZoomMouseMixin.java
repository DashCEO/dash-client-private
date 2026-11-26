package com.dash.client.client.mixin;

import com.dash.client.client.Zoom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class ZoomMouseMixin {
    @Inject(method = "onScroll(JDD)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/ScrollWheelHandler;onMouseScroll(DD)Lorg/joml/Vector2i;"), cancellable = true)
    private void dash$adjustZoom(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (Zoom.scroll(Minecraft.getInstance(), vertical)) {
            ci.cancel();
        }
    }
}
