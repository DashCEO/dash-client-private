package com.dash.client.client.mixin;

import com.dash.client.client.CosmicSky;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
abstract class CosmicCloudMixin {
    @WrapWithCondition(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;addCloudsPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/CloudStatus;Lnet/minecraft/world/phys/Vec3;JFIF)V"))
    private boolean dash$clearCosmicSky(LevelRenderer renderer, FrameGraphBuilder graph,
                                       CloudStatus cloudStatus, Vec3 cameraPosition,
                                       long gameTime, float partialTick, int color, float height) {
        return !CosmicSky.isActive(Minecraft.getInstance().level);
    }
}
