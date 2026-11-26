package com.dash.client.client.mixin;

import com.dash.client.client.XRay;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.vulkanmod.render.chunk.build.RenderRegion", remap = false)
abstract class XRayVulkanRegionMixin {
    @Shadow @Final private BlockState[] blockData;
    @Unique private boolean dash$xray;

    @Inject(method = "loadBlockStates", at = @At("RETURN"))
    private void dash$filterTerrain(CallbackInfo ci) {
        dash$xray = XRay.isRendering();
        if (dash$xray) {
            // This array belongs to the mesh builder. Its source palettes stay intact.
            for (int i = 0; i < blockData.length; i++) {
                blockData[i] = XRay.terrainState(blockData[i]);
            }
        }
    }

    @Inject(method = {"getBrightness", "method_8314"}, at = @At("HEAD"), cancellable = true)
    private void dash$lightOres(CallbackInfoReturnable<Integer> cir) {
        if (dash$xray) {
            cir.setReturnValue(15);
        }
    }
}
