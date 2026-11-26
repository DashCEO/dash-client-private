package com.dash.client.client.mixin;

import com.dash.client.client.DashClientClient;
import com.dash.client.client.ManualHitFeedback;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftManualAttackMixin {
    @Inject(method = "startAttack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/HitResult;getType()Lnet/minecraft/world/phys/HitResult$Type;"),
            cancellable = true)
    private void dash$queueAimAttack(CallbackInfoReturnable<Boolean> ci) {
        if (DashClientClient.queueAimAttack((Minecraft) (Object) this)) ci.setReturnValue(false);
    }

    @Inject(method = "startAttack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;attack(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V"))
    private void dash$observeManualAttack(CallbackInfoReturnable<Boolean> ci) {
        DashClientClient.noteNativeAimAttack();
        ManualHitFeedback.record((Minecraft) (Object) this);
    }
}
