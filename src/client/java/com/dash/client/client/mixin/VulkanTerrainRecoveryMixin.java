package com.dash.client.client.mixin;

import com.dash.client.client.AutoChunkReload;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.vulkanmod.render.chunk.RenderSection", remap = false)
abstract class VulkanTerrainRecoveryMixin {
    @Shadow public int xOffset;
    @Shadow public int yOffset;
    @Shadow public int zOffset;

    @ModifyExpressionValue(
            method = "createCompileTask",
            at = @At(value = "INVOKE", target = "Lnet/vulkanmod/render/chunk/ChunkStatusMap;chunkRenderReady(II)Z"),
            require = 0
    )
    private boolean dash$allowCachedSection(boolean ready) {
        // RenderRegionBuilder already treats absent neighboring columns as empty boundaries.
        return ready || AutoChunkReload.canBuildNearbySection(xOffset, yOffset, zOffset);
    }
}
