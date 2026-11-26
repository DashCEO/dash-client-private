package com.dash.client.client.mixin;

import com.dash.client.client.CosmicSky;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AtmosphericFogEnvironment.class)
abstract class CosmicFogMixin {
    @Inject(method = "getBaseColor", at = @At("RETURN"), cancellable = true)
    private void dash$cosmicHorizon(ClientLevel level, Camera camera, int renderDistance,
                                   float partialTick, CallbackInfoReturnable<Integer> cir) {
        if (CosmicSky.isActive(level)) {
            cir.setReturnValue(CosmicSky.horizonColor());
        }
    }
}
