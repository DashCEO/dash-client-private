package com.dash.client.client.mixin;

import com.dash.client.client.ConsumableBind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class ConsumableUseMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void dashKeepBoundConsumableInHand(CallbackInfoReturnable<Boolean> callback) {
        if (ConsumableBind.blocksHandActions((Minecraft) (Object) this)) callback.setReturnValue(false);
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void dashPreventSecondUse(CallbackInfo callback) {
        if (ConsumableBind.blocksHandActions((Minecraft) (Object) this)) callback.cancel();
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void dashPauseMiningDuringUse(boolean attacking, CallbackInfo callback) {
        if (attacking && ConsumableBind.blocksHandActions((Minecraft) (Object) this)) callback.cancel();
    }

    @Redirect(method = "handleKeybinds", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;releaseUsingItem(Lnet/minecraft/world/entity/player/Player;)V"))
    private void dashKeepBoundConsumableUse(MultiPlayerGameMode controller, Player player) {
        if (!ConsumableBind.holdsUse((Minecraft) (Object) this, player)) controller.releaseUsingItem(player);
    }
}
