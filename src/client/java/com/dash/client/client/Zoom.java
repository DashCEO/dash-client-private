package com.dash.client.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

public final class Zoom {
    private static final double START_MAGNIFICATION = 3.0;
    private static final double MAX_MAGNIFICATION = 50.0;
    private static final double WHEEL_MULTIPLIER = 1.25;

    private static KeyMapping key;
    private static LocalPlayer activePlayer;
    private static double magnification = START_MAGNIFICATION;

    private Zoom() {}

    static void register() {
        key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.dash-client.zoom",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, KeyMapping.Category.MISC) {
            @Override
            public void setDown(boolean down) {
                super.setDown(down);
                if (!down) activePlayer = null;
            }
        });
    }

    public static float applyFov(Minecraft client, float fov) {
        if (!isActive(client) || magnification == 1.0) return fov;

        double halfFov = Math.toRadians(fov) * 0.5;
        return (float) Math.toDegrees(2.0 * Math.atan(Math.tan(halfFov) / magnification));
    }

    public static boolean scroll(Minecraft client, double vertical) {
        if (!isActive(client) || !Double.isFinite(vertical) || vertical == 0.0) return false;

        double steps = client.options.discreteMouseScroll().get() ? Math.signum(vertical) : vertical;
        steps *= client.options.mouseWheelSensitivity().get();
        magnification = Mth.clamp(magnification * Math.pow(WHEEL_MULTIPLIER, steps), 1.0, MAX_MAGNIFICATION);
        return true;
    }

    static void reset() {
        activePlayer = null;
        magnification = START_MAGNIFICATION;
    }

    private static boolean isActive(Minecraft client) {
        if (key == null || SaveMode.isPaused()) return false;

        boolean pressed = key.consumeClick();
        while (key.consumeClick()) {
            // A held binding only needs the first press.
        }

        LocalPlayer player = client.player;
        if (!key.isDown() || player == null || !player.isAlive() || client.level == null
                || client.screen != null || client.getOverlay() != null || client.isPaused()
                || !client.isWindowActive() || !client.mouseHandler.isMouseGrabbed()) {
            activePlayer = null;
            return false;
        }
        if (activePlayer != player && pressed) {
            activePlayer = player;
            magnification = START_MAGNIFICATION;
        }
        return activePlayer == player;
    }
}
