package com.dash.client.client.mixin;

import com.dash.client.client.DashClientClient;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityRenderer.class)
abstract class NoFireEntityRendererMixin {
    @ModifyExpressionValue(method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;displayFireAnimation()Z"))
    private boolean dash$showFlames(boolean visible) {
        return visible && !DashClientClient.isNoFireEnabled();
    }

    @ModifyExpressionValue(method = "getBlockLightLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;isOnFire()Z"))
    private boolean dash$lightFromFlames(boolean burning) {
        return burning && !DashClientClient.isNoFireEnabled();
    }
}
