package com.dash.client.client.mixin;

import com.dash.client.client.DashHudLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class DashHudClickMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "onButton", at = @At("HEAD"))
    private void dash$recordHudClick(long window, MouseButtonInfo info, int action, CallbackInfo callback) {
        if (window == minecraft.getWindow().handle() && action == GLFW.GLFW_PRESS && info.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            DashHudLayout.recordLeftClick(minecraft);
        }
    }
}
