package com.dash.client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;

public final class Velocity {
    private static final int MATCH_TICKS = 2;
    private static final int CORRECTION_TICKS = 40;
    private static final double MIN_CORRECTION_DISTANCE_SQUARED = 0.05 * 0.05;
    private static boolean enabled;
    private static float force;
    private static LocalPlayer pendingPlayer;
    private static int damageTick;
    private static LocalPlayer lastReducedPlayer;
    private static int reducedTick;
    private static ClientPacketListener fallbackConnection;

    private Velocity() {}

    static boolean isEnabled() { return enabled; }
    static float force() { return force; }
    static void setForce(float value) { force = value; }

    static void toggle() {
        enabled = !enabled;
        resetConnection();
    }

    static void disable() {
        enabled = false;
        resetConnection();
    }

    public static boolean isActive() {
        return enabled && !SaveMode.isPaused();
    }

    public static boolean isServerKnockback() {
        return fallbackConnection != null && fallbackConnection == Minecraft.getInstance().getConnection();
    }

    static void reset() {
        pendingPlayer = null;
        lastReducedPlayer = null;
    }

    static void resetConnection() {
        reset();
        fallbackConnection = null;
    }

    public static void onDamage(ClientboundDamageEventPacket packet) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || packet.entityId() != player.getId()) return;

        pendingPlayer = null;
        if (isActive() && !isServerKnockback() && force < 1.0F && isCombatDamage(packet)) {
            pendingPlayer = player;
            damageTick = player.tickCount;
        }
    }

    private static boolean isCombatDamage(ClientboundDamageEventPacket packet) {
        var type = packet.sourceType();
        if (type.is(DamageTypeTags.IS_EXPLOSION) || type.is(DamageTypeTags.NO_IMPACT)) return false;

        // Older translated protocols can report an ordinary hurt event without a source.
        boolean legacyHurt = type.is(DamageTypes.GENERIC) && packet.sourceCauseId() < 0
                && packet.sourceDirectId() < 0 && packet.sourcePosition().isEmpty();
        return legacyHurt || type.is(DamageTypeTags.IS_PROJECTILE) || type.is(DamageTypeTags.IS_PLAYER_ATTACK)
                || type.is(DamageTypes.PLAYER_ATTACK) || type.is(DamageTypes.MACE_SMASH)
                || type.is(DamageTypes.MOB_ATTACK) || type.is(DamageTypes.MOB_ATTACK_NO_AGGRO)
                || type.is(DamageTypes.STING) || type.is(DamageTypes.ARROW);
    }

    public static Vec3 adjustMotion(Entity entity, Vec3 received) {
        if (pendingPlayer == null || entity != pendingPlayer) return received;

        LocalPlayer player = pendingPlayer;
        pendingPlayer = null;
        int elapsed = player.tickCount - damageTick;
        if (!isActive() || isServerKnockback() || player != Minecraft.getInstance().player
                || elapsed < 0 || elapsed > MATCH_TICKS) {
            return received;
        }
        Vec3 adjusted = player.getDeltaMovement().lerp(received, force);
        if (adjusted.distanceToSqr(received) > 1.0E-12) {
            lastReducedPlayer = player;
            reducedTick = player.tickCount;
        }
        return adjusted;
    }

    public static void onPositionCorrection(ClientboundPlayerPositionPacket packet) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        pendingPlayer = null;
        if (player == null || player != lastReducedPlayer) {
            return;
        }

        int elapsed = player.tickCount - reducedTick;
        if (!isActive() || elapsed < 0 || elapsed > CORRECTION_TICKS) {
            reset();
            return;
        }

        PositionMoveRotation current = PositionMoveRotation.of(player);
        PositionMoveRotation corrected = PositionMoveRotation.calculateAbsolute(current,
                packet.change(), packet.relatives());
        if (current.position().distanceToSqr(corrected.position()) <= MIN_CORRECTION_DISTANCE_SQUARED) {
            return;
        }

        // Stop reducing future impulses when the server corrects movement after a reduced hit.
        // Keep this fallback across respawns and pause/resume on the same connection.
        fallbackConnection = client.getConnection();
        reset();
        if (fallbackConnection != null) {
            client.gui.getChat().addMessage(Component.literal(
                    "[Dash] Velocity: сервер скоригував позицію після удару. "
                            + "Звичайне відкидання увімкнено до перепідключення або перемикання Velocity."));
        }
    }
}
