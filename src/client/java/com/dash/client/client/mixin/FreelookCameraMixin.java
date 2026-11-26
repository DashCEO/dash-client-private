package com.dash.client.client.mixin;

import com.dash.client.client.Freelook;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Camera.class)
abstract class FreelookCameraMixin {
    @WrapOperation(method = "setup(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;ZZF)V",
            at = {
                    @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 0),
                    @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 1)
            })
    private void dash$rotateFreelookCamera(Camera camera, float yaw, float pitch, Operation<Void> original,
                                           Level level, Entity entity, boolean detached, boolean mirrored,
                                           float partialTick) {
        Minecraft client = Minecraft.getInstance();
        if (entity == client.player && level == client.level && Freelook.isActive(client)) {
            original.call(camera, Freelook.yaw(), Freelook.pitch());
        } else {
            original.call(camera, yaw, pitch);
        }
    }
}
