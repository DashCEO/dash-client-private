package com.dash.client.client.mixin;

import com.dash.client.client.DashClientClient;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ScreenEffectRenderer.class)
abstract class NoFireScreenEffectMixin {
    @ModifyExpressionValue(method = "renderScreenEffect",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isOnFire()Z"))
    private boolean dash$showFireOverlay(boolean burning) {
        return burning && !DashClientClient.isNoFireEnabled();
    }
}
