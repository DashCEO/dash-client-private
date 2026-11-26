package com.dash.client.client.mixin;

import com.dash.client.client.DashBindInput;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class DashBindKeyboardMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "keyPress", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lcom/mojang/blaze3d/platform/FramerateLimitTracker;onInputReceived()V"), cancellable = true)
    private void dash$handleBinding(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (DashBindInput.keyboard(minecraft, action, event)) ci.cancel();
    }
}
