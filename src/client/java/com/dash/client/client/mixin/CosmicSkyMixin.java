package com.dash.client.client.mixin;

import com.dash.client.client.CosmicSky;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
abstract class CosmicSkyMixin {
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void dash$cosmicSky(ClientLevel level, float partialTick, Camera camera,
                               SkyRenderState state, CallbackInfo ci) {
        CosmicSky.apply(level, state);
    }
}
