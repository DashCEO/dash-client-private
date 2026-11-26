package com.dash.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
abstract class RtxFrameLimitMixin {
    @Unique private static final int dash$RTX_CAP = Integer.getInteger("dash.rtxFpsCap", 0);

    @ModifyExpressionValue(method = "runTick(Z)V", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/FramerateLimitTracker;getFramerateLimit()I"))
    private int dash$limitRtxFrames(int limit) {
        if (dash$RTX_CAP > 0 && dash$RTX_CAP < 260) {
            return Math.min(limit, dash$RTX_CAP);
        }
        // Minecraft uses 260 to skip its limiter. Lower limits still use the vanilla path.
        if (limit == 260 && dash$RTX_CAP > 260) {
            RenderSystem.limitDisplayFPS(dash$RTX_CAP);
        }
        return limit;
    }
}
