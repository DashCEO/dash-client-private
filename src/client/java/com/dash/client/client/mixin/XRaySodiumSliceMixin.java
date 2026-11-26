package com.dash.client.client.mixin;

import com.dash.client.client.XRay;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.LevelSlice", remap = false)
abstract class XRaySodiumSliceMixin {
    @Unique private boolean dash$xray;

    @Inject(method = "copyData", at = @At("RETURN"))
    private void dash$captureXRay(CallbackInfo ci) {
        dash$xray = XRay.isRendering();
    }

    @Inject(method = {
            "getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;",
            "getBlockState(III)Lnet/minecraft/class_2680;"
    }, at = @At("RETURN"), cancellable = true)
    private void dash$filterTerrain(CallbackInfoReturnable<BlockState> cir) {
        if (dash$xray) {
            cir.setReturnValue(XRay.terrainState(cir.getReturnValue()));
        }
    }

    @Inject(method = {"getBrightness", "method_8314"}, at = @At("HEAD"), cancellable = true)
    private void dash$lightOres(CallbackInfoReturnable<Integer> cir) {
        if (dash$xray) {
            cir.setReturnValue(15);
        }
    }
}
