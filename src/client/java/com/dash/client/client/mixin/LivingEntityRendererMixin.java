package com.dash.client.client.mixin;

import com.dash.client.client.DashClientClient;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {
    @ModifyExpressionValue(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
            at = @At(value = "CONSTANT", args = "intValue=654311423")
    )
    private int dashRevealedPlayerTint(int original, @Local(argsOnly = true) LivingEntityRenderState state) {
        var viewer = Minecraft.getInstance().player;
        if (state instanceof AvatarRenderState avatar && avatar.entityType == EntityType.PLAYER
                && !avatar.isSpectator && viewer != null && avatar.id != viewer.getId()
                && DashClientClient.isUnInvisibilityEnabled()) {
            return 0x80FFFFFF;
        }
        return original;
    }
}
