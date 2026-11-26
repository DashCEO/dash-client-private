package com.dash.client.client.mixin;

import com.dash.client.client.DashBindInput;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MouseHandler.class)
abstract class DashBindMouseMixin {
    @Shadow @Final private Minecraft minecraft;

    @WrapOperation(method = "onButton", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/KeyMapping;set(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)V"))
    private void dash$handleBinding(InputConstants.Key key, boolean down, Operation<Void> original) {
        if (!DashBindInput.mouseState(minecraft, key, down)) original.call(key, down);
    }

    @WrapOperation(method = "onButton", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/KeyMapping;click(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V"))
    private void dash$ignoreClaimedClick(InputConstants.Key key, Operation<Void> original) {
        if (!DashBindInput.mouseClickClaimed(key)) original.call(key);
    }
}
