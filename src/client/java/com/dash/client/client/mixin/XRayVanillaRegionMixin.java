package com.dash.client.client.mixin;

import com.dash.client.client.XRay;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSectionRegion.class)
abstract class XRayVanillaRegionMixin implements BlockAndTintGetter {
    @Unique private boolean dash$xray;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void dash$captureXRay(CallbackInfo ci) {
        dash$xray = XRay.isRendering();
    }

    @Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true)
    private void dash$filterTerrain(CallbackInfoReturnable<BlockState> cir) {
        if (dash$xray) {
            cir.setReturnValue(XRay.terrainState(cir.getReturnValue()));
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void dash$hideFluids(CallbackInfoReturnable<FluidState> cir) {
        if (dash$xray) {
            cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
        }
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return dash$xray ? 15 : BlockAndTintGetter.super.getBrightness(layer, pos);
    }
}
