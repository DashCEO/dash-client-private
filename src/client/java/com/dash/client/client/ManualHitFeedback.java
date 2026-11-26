package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import com.dash.client.client.mixin.LivingEntityHealthAccessor;
import com.mojang.logging.LogUtils;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import org.slf4j.Logger;

public final class ManualHitFeedback {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long RESPONSE_NANOS = 1_250_000_000L;
    private static final long CRIT_SIGNAL_WINDOW_NANOS = 250_000_000L;
    private static final long DISPLAY_NANOS = 4_500_000_000L;
    private static Hit hit;

    private ManualHitFeedback() {}

    public static void record(Minecraft client) {
        if (client.hitResult instanceof EntityHitResult selection) {
            record(client, selection, DashClientClient.manualAttackSource());
        }
    }

    static void recordTriggerBot(Minecraft client, EntityHitResult selection) {
        record(client, selection, "trigger_bot");
    }

    private static void record(Minecraft client, EntityHitResult selection, String source) {
        LocalPlayer player = client.player;
        if (!HudSettings.hitFeedback() || SaveMode.isPaused() || player == null
                || client.getCameraEntity() != player || player.isSpectator()
                || !(selection.getEntity() instanceof LivingEntity target)
                || target == player || target.level() != client.level || !target.isAlive()) {
            return;
        }

        long now = System.nanoTime();
        boolean overlap = hit != null && hit.player == player && hit.target == target
                && now - hit.startedAt <= RESPONSE_NANOS && !hit.confirmed();
        logOnce(hit);
        hit = new Hit(player, target, selection.getLocation().distanceTo(player.getEyePosition()), source,
                now, AimCritTiming.isReady(player), overlap || DashClientClient.hasUntrackedAttacks());
    }

    public static void onDamage(Minecraft client, ClientboundDamageEventPacket packet) {
        Hit current = pending(client, packet.entityId());
        if (current == null) return;

        int playerId = current.player.getId();
        boolean localMelee = packet.sourceCauseId() == playerId && packet.sourceDirectId() == playerId
                && (packet.sourceType().is(DamageTypes.PLAYER_ATTACK) || packet.sourceType().is(DamageTypes.MACE_SMASH));
        // A second damage event can make the health update ambiguous.
        current.ambiguous |= !localMelee || current.localDamage || DashClientClient.hasUntrackedAttacks();
        if (localMelee && !current.localDamage) current.localDamageAt = System.nanoTime();
        current.localDamage |= localMelee;
    }

    public static void onAnimate(Minecraft client, ClientboundAnimatePacket packet) {
        if (packet.getAction() != ClientboundAnimatePacket.CRITICAL_HIT) return;
        Hit current = pending(client, packet.getId());
        if (current != null) current.critSignalAt = System.nanoTime();
    }

    public static void onEntityData(Minecraft client, ClientboundSetEntityDataPacket packet) {
        Hit current = pending(client, packet.id());
        if (current == null) return;

        int healthId = LivingEntityHealthAccessor.dash$healthData().id();
        for (var entry : packet.packedItems()) {
            if (entry.id() == healthId && entry.value() instanceof Float health && Float.isFinite(health)) {
                if (current.healthLoss == null) {
                    current.observedHealth = health;
                    float lost = current.startHealth - health;
                    if (lost > 0.0F) {
                        current.healthLoss = Readout.format("−", lost, "", "");
                    }
                }
                return;
            }
        }
    }

    private static Hit pending(Minecraft client, int targetId) {
        Hit current = hit;
        if (current == null || current.logged || client.player != current.player || client.level != current.target.level()
                || current.target.getId() != targetId || client.level.getEntity(targetId) != current.target
                || System.nanoTime() - current.startedAt > RESPONSE_NANOS) {
            return null;
        }
        return current;
    }

    static void tick(Minecraft client) {
        Hit current = hit;
        if (current == null) return;

        long age = System.nanoTime() - current.startedAt;
        if (age > DISPLAY_NANOS || client.player != current.player || client.level != current.target.level()) {
            clear();
        } else if (age > RESPONSE_NANOS) {
            logOnce(current);
        }
    }

    static int draw(GuiGraphics graphics, Minecraft client, int x, int y) {
        Hit current = hit;
        if (current == null) return y;

        long age = System.nanoTime() - current.startedAt;
        if (age > DISPLAY_NANOS || client.player != current.player || client.level != current.target.level()) {
            clear();
            return y;
        }

        DashHud.drawMetric(graphics, client.font, x, y, text("ХІТБОКС", "ХИТБОКС", "HITBOX"),
                current.distance.display(), HudSettings.accent());
        if (current.healthLoss != null) {
            boolean confirmed = current.confirmed();
            DashHud.drawMetric(graphics, client.font, x, y + 15, confirmed ? "HP" : "HP?",
                    current.healthLoss.display(), confirmed ? HudSettings.secondary() : DashHud.MUTED);
        } else {
            String status = age <= RESPONSE_NANOS ? text("Очікування", "Ожидание", "Pending")
                    : text("Невідомо", "Неизвестно", "Unknown");
            DashHud.drawMetric(graphics, client.font, x, y + 15, "HP", status, DashHud.MUTED);
        }
        if (current.critObserved()) {
            DashHud.drawMetric(graphics, client.font, x, y + 30, "CRIT", text("СИГНАЛ", "СИГНАЛ", "SIGNAL"),
                    HudSettings.secondary());
            return y + 45;
        }
        return y + 30;
    }

    static void clear() {
        logOnce(hit);
        hit = null;
    }

    private static void logOnce(Hit current) {
        if (current == null || current.logged) return;
        current.logged = true;
        LOGGER.info("[Dash Last Hit] target_id={} distance_blocks={} hp_before={} hp_after={} status={} source={} crit_ready={} crit_signal={} crit_observed={}",
                current.target.getId(), String.format(Locale.ROOT, "%.6f", current.distanceBlocks),
                healthText(current.startHealth), healthText(current.observedHealth), current.status(), current.source,
                current.critReady, current.critSignalAt != 0, current.critObserved());
    }

    private static String healthText(float health) {
        return Float.isFinite(health) ? Float.toString(health) : "unknown";
    }

    private static final class Hit {
        final LocalPlayer player;
        final LivingEntity target;
        final Readout distance;
        final double distanceBlocks;
        final String source;
        final float startHealth;
        final long startedAt;
        final boolean critReady;
        boolean localDamage;
        long localDamageAt;
        long critSignalAt;
        boolean ambiguous;
        boolean logged;
        float observedHealth = Float.NaN;
        Readout healthLoss;

        Hit(LocalPlayer player, LivingEntity target, double distance, String source, long startedAt, boolean critReady,
            boolean ambiguous) {
            this.player = player;
            this.target = target;
            this.distance = Readout.format("", distance, " бл.", " bl.");
            this.distanceBlocks = distance;
            this.source = source;
            this.startHealth = target.getHealth();
            this.startedAt = startedAt;
            this.critReady = critReady;
            this.ambiguous = ambiguous;
        }

        boolean confirmed() {
            return localDamage && healthLoss != null && !ambiguous;
        }

        boolean critObserved() {
            // The animation identifies the target. Attribute it only alongside the local, unambiguous damage response.
            return confirmed() && critSignalAt != 0 && localDamageAt != 0
                    && Math.abs(critSignalAt - localDamageAt) <= CRIT_SIGNAL_WINDOW_NANOS;
        }

        String status() {
            if (confirmed()) return "confirmed";
            if (healthLoss != null) return "unattributed_change";
            return Float.isFinite(observedHealth) ? "no_hp_decrease" : "no_hp_update";
        }
    }

    private record Readout(String cyrillic, String english) {
        static Readout format(String prefix, double value, String cyrillicUnit, String englishUnit) {
            String number = prefix + String.format(Locale.ROOT, "%.2f", value);
            return new Readout(number.replace('.', ',') + cyrillicUnit, number + englishUnit);
        }

        String display() {
            return text(cyrillic, cyrillic, english);
        }
    }
}
