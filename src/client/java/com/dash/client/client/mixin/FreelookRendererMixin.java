package com.dash.client.client.mixin;

import com.dash.client.client.Freelook;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
abstract class FreelookRendererMixin {
    @WrapOperation(method = {
            "tickFov()V",
            "renderItemInHand(FZLorg/joml/Matrix4f;)V",
            "updateCamera(Lnet/minecraft/client/DeltaTracker;)V",
            "renderLevel(Lnet/minecraft/client/DeltaTracker;)V"
    }, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Options;getCameraType()Lnet/minecraft/client/CameraType;"))
    private CameraType dash$freelookPerspective(Options options, Operation<CameraType> original) {
        return Freelook.renderPerspective(Minecraft.getInstance(), original.call(options));
    }
}
