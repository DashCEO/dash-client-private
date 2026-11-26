package com.dash.client.client.mixin;

import com.dash.client.client.CosmicSky;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BiomeColors.class)
abstract class CosmicGrassMixin {
    @Inject(method = "getAverageGrassColor", at = @At("RETURN"), cancellable = true)
    private static void dash$shadeGrass(BlockAndTintGetter level, BlockPos pos,
                                       CallbackInfoReturnable<Integer> cir) {
        int original = cir.getReturnValueI();
        int tint = CosmicSky.grassColor(original);
        if (tint != original) {
            cir.setReturnValue(tint);
        }
    }
}
