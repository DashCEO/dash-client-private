package com.dash.client.client;

import com.dash.client.client.mixin.LocalPlayerSprintAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.Properties;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

final class TriggerBot {
    static final double MIN_RANGE = 2.0;
    static final double MAX_RANGE = 5.0;
    private static final double DEFAULT_RANGE = 3.0;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final EnumSet<TargetType> targetTypes = EnumSet.of(TargetType.PLAYERS);
    private static double range = DEFAULT_RANGE;
    private static boolean settingsDirty;
    private static KeyMapping toggleKey;
    private static boolean enabled;

    private TriggerBot() {}

    static void register() {
        loadSettings();
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.dash-client.trigger_bot",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, KeyMapping.Category.MISC));
    }

    static boolean isEnabled() {
        return enabled;
    }

    static void toggle() {
        enabled = !enabled;
    }

    static void disable() {
        enabled = false;
    }

    static double range() {
        return range;
    }

    static void setRange(double value) {
        double selected = normalizeRange(value);
        if (selected != range) {
            range = selected;
            settingsDirty = true;
        }
    }

    static boolean targets(TargetType type) {
        return targetTypes.contains(type);
    }

    static void toggleTarget(TargetType type) {
        if (!targetTypes.remove(type)) {
            targetTypes.add(type);
        }
        settingsDirty = true;
    }

    private static double normalizeRange(double value) {
        if (!Double.isFinite(value)) return DEFAULT_RANGE;
        return Math.round(Mth.clamp(value, MIN_RANGE, MAX_RANGE) * 10.0) / 10.0;
    }

    static void loadSettings() {
        range = DEFAULT_RANGE;
        targetTypes.clear();
        targetTypes.add(TargetType.PLAYERS);
        settingsDirty = false;
        Path path = settingsFile();
        if (!Files.isRegularFile(path)) return;

        Properties settings = new Properties();
        try (var input = Files.newBufferedReader(path)) {
            settings.load(input);
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.warn("Could not read Trigger Bot settings", exception);
            return;
        }
        try {
            range = normalizeRange(Double.parseDouble(settings.getProperty("range", "3.0")));
        } catch (NumberFormatException ignored) {
            range = DEFAULT_RANGE;
        }
        for (TargetType type : TargetType.values()) {
            String selected = settings.getProperty(type.key);
            if ("true".equalsIgnoreCase(selected)) {
                targetTypes.add(type);
            } else if ("false".equalsIgnoreCase(selected)) {
                targetTypes.remove(type);
            }
        }
    }

    static void saveSettings() {
        if (!settingsDirty) return;
        Properties settings = new Properties();
        settings.setProperty("range", Double.toString(range));
        for (TargetType type : TargetType.values()) {
            settings.setProperty(type.key, Boolean.toString(targets(type)));
        }
        Path path = settingsFile();
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), "dash-trigger-bot-", ".tmp");
            try (var output = Files.newBufferedWriter(temporary)) {
                settings.store(output, "Dash Client Trigger Bot");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            settingsDirty = false;
        } catch (IOException exception) {
            LOGGER.warn("Could not save Trigger Bot settings", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    LOGGER.debug("Could not remove temporary Trigger Bot settings", exception);
                }
            }
        }
    }

    private static Path settingsFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("dash-trigger-bot.properties");
    }

    static void handleInput(Minecraft client) {
        while (toggleKey.consumeClick()) {
            if (canInteract(client)) {
                toggle();
            }
        }
    }

    static void tick(Minecraft client) {
        if (!enabled || !canInteract(client) || ConsumableBind.busy(client) || client.getCameraEntity() != client.player
                || !client.mouseHandler.isMouseGrabbed() || Freelook.isActive(client)) {
            return;
        }

        var player = client.player;
        if (!player.isAlive() || player.isSpectator() || player.isUsingItem() || client.gameMode.isDestroying()
                || player.getAttackStrengthScale(.5F) < 1.0F) {
            return;
        }
        if (!(player.raycastHitResult(1.0F, player) instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof LivingEntity target) || target == player
                || target.level() != client.level || !target.isAlive() || target.isSpectator()
                || Friends.isFriendPlayer(target) || !targets(targetType(target)) || !target.isAttackable()) {
            return;
        }
        // The chosen distance can shorten the normal raycast and melee range.
        if (player.getEyePosition(1.0F).distanceToSqr(hit.getLocation()) > range * range
                || !player.isWithinAttackRange(target.getBoundingBox(), 0.0)) {
            return;
        }

        boolean jumping = !player.onGround() && !player.isInWater() && !player.isInLava()
                && !player.onClimbable() && !player.isPassenger() && !player.getAbilities().flying
                && !player.isFallFlying() && !player.isMobilityRestricted();
        if (jumping) {
            if (player.getDeltaMovement().y >= 0 || player.fallDistance <= 0) {
                return;
            }
            player.setSprinting(false);
            ((LocalPlayerSprintAccessor) player).dash$syncSprinting();
        }

        ManualHitFeedback.recordTriggerBot(client, hit);
        client.gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private static boolean canInteract(Minecraft client) {
        return client.player != null && client.level != null && client.gameMode != null
                && client.screen == null && client.getOverlay() == null && !client.isPaused()
                && client.isWindowActive() && !SaveMode.isPaused();
    }

    private static TargetType targetType(LivingEntity target) {
        if (target instanceof Player) return TargetType.PLAYERS;
        if (!(target instanceof Mob)) return null;
        if (target instanceof NeutralMob) return TargetType.NEUTRAL;
        return target instanceof Enemy ? TargetType.HOSTILE : TargetType.PASSIVE;
    }

    enum TargetType {
        PLAYERS("players", "Гравці"),
        HOSTILE("hostile", "Ворожі"),
        NEUTRAL("neutral", "Нейтральні"),
        PASSIVE("passive", "Мирні");

        final String key;
        final String label;

        TargetType(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }
}
