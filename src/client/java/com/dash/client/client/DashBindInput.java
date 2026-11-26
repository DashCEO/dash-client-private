package com.dash.client.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

public final class DashBindInput {
    private static final List<Binding> bindings = new ArrayList<>();
    private static final Set<InputConstants.Key> claimedKeyboard = new HashSet<>();
    private static final Set<InputConstants.Key> capturedKeyboard = new HashSet<>();
    private static final Set<InputConstants.Key> claimedMouse = new HashSet<>();

    private DashBindInput() {}

    static void register(KeyMapping key, Predicate<Minecraft> onPress) {
        bindings.add(new Binding(key, onPress));
    }

    static void tick(Minecraft client) {
        // Vanilla still updates its KeyMappings when movement shares a key. Dash dispatches on the input edge.
        for (Binding binding : bindings) {
            while (binding.key().consumeClick()) {}
        }
        if (!client.isWindowActive()) {
            reset();
        }
    }

    static void reset() {
        claimedKeyboard.clear();
        capturedKeyboard.clear();
        claimedMouse.clear();
    }

    public static boolean keyboard(Minecraft client, int action, KeyEvent event) {
        if (SaveMode.isPaused()) return false;
        InputConstants.Key key = InputConstants.getKey(event);
        if (action == GLFW.GLFW_RELEASE) {
            capturedKeyboard.remove(key);
            if (!claimedKeyboard.remove(key)) return false;
            setMovement(client.options, key, false);
            return canDispatch(client);
        }
        if (action == GLFW.GLFW_REPEAT) {
            return capturedKeyboard.contains(key) || canDispatch(client) && claimedKeyboard.contains(key);
        }
        if (action != GLFW.GLFW_PRESS) return false;
        capturedKeyboard.remove(key);
        claimedKeyboard.remove(key);
        if (client.screen instanceof KeyCapture capture && capture.captureDashKey(event)) {
            capturedKeyboard.add(key);
            return true;
        }
        if (!dispatch(client, key)) return false;
        if (event.key() != GLFW.GLFW_KEY_ESCAPE && !hasVanillaAction(client.options, key)) return false;
        claimedKeyboard.add(key);
        setMovement(client.options, key, true);
        return true;
    }

    public static boolean mouseState(Minecraft client, InputConstants.Key key, boolean down) {
        if (SaveMode.isPaused()) return false;
        claimedMouse.remove(key);
        if (!down || !client.mouseHandler.isMouseGrabbed() || !dispatch(client, key)
                || !hasVanillaAction(client.options, key)) return false;
        claimedMouse.add(key);
        setMovement(client.options, key, true);
        return true;
    }

    public static boolean mouseClickClaimed(InputConstants.Key key) {
        return !SaveMode.isPaused() && claimedMouse.contains(key);
    }

    private static boolean dispatch(Minecraft client, InputConstants.Key key) {
        if (!canDispatch(client)) return false;
        for (Binding binding : bindings) {
            if (matches(binding.key(), key)) {
                return !InventoryBindSupport.conflicts(client, binding.key()) && binding.onPress().test(client);
            }
        }
        return false;
    }

    private static boolean canDispatch(Minecraft client) {
        return client.isWindowActive() && client.player != null && client.player.isAlive()
                && !client.player.isSpectator() && client.level != null && client.gameMode != null
                && client.getConnection() != null && client.screen == null && client.getOverlay() == null
                && !client.isPaused() && !SaveMode.isPaused();
    }

    private static boolean hasVanillaAction(Options options, InputConstants.Key key) {
        if (matches(options.keyDebugModifier, key) || matches(options.keyDebugOverlay, key)) return true;
        for (KeyMapping mapping : options.keyMappings) {
            if (!SaveMode.isManagedBinding(mapping) && !isMovement(options, mapping) && matches(mapping, key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMovement(Options options, KeyMapping mapping) {
        return mapping == options.keyUp || mapping == options.keyDown || mapping == options.keyLeft
                || mapping == options.keyRight || mapping == options.keyJump || mapping == options.keyShift
                || mapping == options.keySprint;
    }

    private static void setMovement(Options options, InputConstants.Key key, boolean down) {
        for (KeyMapping mapping : options.keyMappings) {
            if (isMovement(options, mapping) && matches(mapping, key)) mapping.setDown(down);
        }
    }

    private static boolean matches(KeyMapping mapping, InputConstants.Key key) {
        return !mapping.isUnbound() && KeyBindingHelper.getBoundKeyOf(mapping).equals(key);
    }

    public interface KeyCapture {
        boolean captureDashKey(KeyEvent event);
    }

    private record Binding(KeyMapping key, Predicate<Minecraft> onPress) {}
}
