package com.dash.client.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

public final class Freelook {
    private static KeyMapping key;
    private static boolean enabled = true;
    private static LocalPlayer activePlayer;
    private static ClientLevel activeLevel;
    private static float yaw;
    private static float pitch;

    private Freelook() {}

    static void register() {
        key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.dash-client.freelook",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, KeyMapping.Category.MISC));
    }

    public static boolean isEnabled() {
        return enabled;
    }

    static void toggle() {
        enabled = !enabled;
        reset();
    }

    static void reset() {
        activePlayer = null;
        activeLevel = null;
        consumePresses();
    }

    public static boolean isActive(Minecraft client) {
        boolean pressed = consumePresses();
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (!enabled || key == null || !key.isDown() || SaveMode.isPaused()
                || player == null || level == null || !player.isAlive()
                || client.getCameraEntity() != player || client.screen != null || client.getOverlay() != null
                || client.isPaused()
                || !client.isWindowActive() || !client.mouseHandler.isMouseGrabbed()
                || StandaloneFreecam.isActive()) {
            activePlayer = null;
            activeLevel = null;
            return false;
        }
        if (activePlayer != player || activeLevel != level) {
            activePlayer = null;
            activeLevel = null;
            if (!pressed) {
                return false;
            }
            activePlayer = player;
            activeLevel = level;
            yaw = player.getYRot();
            pitch = player.getXRot();
        }
        return true;
    }

    public static boolean turn(Minecraft client, double deltaYaw, double deltaPitch) {
        if (!isActive(client)) {
            return false;
        }
        yaw = Mth.wrapDegrees(yaw + (float) deltaYaw * 0.15F);
        pitch = Mth.clamp(pitch + (float) deltaPitch * 0.15F, -90.0F, 90.0F);
        return true;
    }

    public static CameraType renderPerspective(Minecraft client, CameraType perspective) {
        return perspective == CameraType.FIRST_PERSON && isActive(client)
                ? CameraType.THIRD_PERSON_BACK : perspective;
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    private static boolean consumePresses() {
        if (key == null || !key.consumeClick()) {
            return false;
        }
        while (key.consumeClick()) {
            // This is a held binding, so discard queued repeats.
        }
        return true;
    }

    private static final class StandaloneFreecam {
        private static final Method IS_ENABLED = findIsEnabled();

        private static Method findIsEnabled() {
            if (!FabricLoader.getInstance().isModLoaded("basefinder")) {
                return null;
            }
            try {
                return Class.forName("com.basefinder.FreecamManager").getMethod("isEnabled");
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }

        private static boolean isActive() {
            if (IS_ENABLED == null) {
                return false;
            }
            try {
                return Boolean.TRUE.equals(IS_ENABLED.invoke(null));
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        }
    }
}
