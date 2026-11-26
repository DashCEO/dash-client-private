package com.dash.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
abstract class RtxBorderlessWindowMixin {
    @Unique private static final boolean dash$BORDERLESS = Boolean.getBoolean("dash.rtxBorderless");

    @Shadow @Final private long handle;
    @Shadow private int x;
    @Shadow private int y;
    @Shadow private int width;
    @Shadow private int height;
    @Shadow private int windowedX;
    @Shadow private int windowedY;
    @Shadow private int windowedWidth;
    @Shadow private int windowedHeight;
    @Shadow private boolean fullscreen;
    @Shadow private boolean actuallyFullscreen;
    @Shadow public abstract Monitor findBestMonitor();

    @Unique private boolean dash$borderlessActive;
    @Unique private int dash$originalDecoration;

    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/DisplayData;isFullscreen()Z"))
    private boolean dash$createWindowedContext(boolean requestedFullscreen) {
        return requestedFullscreen && !dash$BORDERLESS;
    }

    @Inject(method = "setMode", at = @At("HEAD"), cancellable = true)
    private void dash$applyBorderlessMode(CallbackInfo ci) {
        if (!dash$BORDERLESS) return;
        if (!fullscreen) {
            dash$restoreDecoration();
            return;
        }

        Monitor monitor = findBestMonitor();
        long display = monitor == null ? GLFW.glfwGetPrimaryMonitor() : monitor.getMonitor();
        var mode = display == 0 ? null : GLFW.glfwGetVideoMode(display);
        if (mode == null) {
            fullscreen = false;
            actuallyFullscreen = false;
            dash$restoreDecoration();
            return;
        }

        if (!dash$borderlessActive) {
            windowedX = x;
            windowedY = y;
            windowedWidth = width;
            windowedHeight = height;
            dash$originalDecoration = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_DECORATED);
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
            dash$borderlessActive = true;
        }
        int[] monitorX = new int[1];
        int[] monitorY = new int[1];
        GLFW.glfwGetMonitorPos(display, monitorX, monitorY);
        x = monitorX[0];
        y = monitorY[0];
        width = mode.width();
        height = mode.height();
        // Keep the existing window and context, without entering a monitor video mode.
        GLFW.glfwSetWindowMonitor(handle, 0, x, y, width, height, GLFW.GLFW_DONT_CARE);
        ci.cancel();
    }

    @Unique
    private void dash$restoreDecoration() {
        if (dash$borderlessActive) {
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, dash$originalDecoration);
            dash$borderlessActive = false;
        }
    }
}
