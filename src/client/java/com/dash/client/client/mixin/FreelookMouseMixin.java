package com.dash.client.client.mixin;

import com.dash.client.client.AimAssist;
import com.dash.client.client.DashClientClient;
import com.dash.client.client.Freelook;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class FreelookMouseMixin {
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "handleAccumulatedMovement()V", at = @At("HEAD"))
    private void dash$updateFreelook(CallbackInfo ci) {
        Freelook.isActive(Minecraft.getInstance());
    }

    @WrapOperation(method = "turnPlayer(D)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void dash$turnFreelook(LocalPlayer player, double yaw, double pitch, Operation<Void> original) {
        Minecraft client = Minecraft.getInstance();
        if (Freelook.turn(client, yaw, pitch)) {
            DashClientClient.cancelAimInput();
            return;
        }
        boolean physicalMovement = accumulatedDX != 0.0 || accumulatedDY != 0.0;
        AimAssist.Input corrected = DashClientClient.correctAimInput(client, player, yaw, pitch, physicalMovement);
        if (corrected != null) {
            yaw = corrected.yaw();
            pitch = corrected.pitch();
        }
        original.call(player, yaw, pitch);
    }
}
