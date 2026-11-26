package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;

public final class DashTelemetry {
    private static final int WIDTH = 104;
    private static final int ROW_HEIGHT = 13;
    private static final int RADAR_RANGE = 32;
    private static final int MAX_RADAR_DOTS = 12;
    private static final int MAX_COOLDOWNS = 3;
    private static final int TIME_WINDOW = 6;
    private static final long STALE_TIME_NANOS = 30_000_000_000L;

    private static final long[] GAME_TIMES = new long[TIME_WINDOW];
    private static final long[] RECEIVED_TIMES = new long[TIME_WINDOW];
    private static final List<Cooldown> COOLDOWNS = new ArrayList<>(MAX_COOLDOWNS);
    private static final Set<Identifier> COOLDOWN_GROUPS = new HashSet<>();
    private static final List<KeyMapping> DASH_KEYS = new ArrayList<>();

    private static ClientLevel level;
    private static int timeSamples;
    private static int nextTimeSample;
    private static long lastTimePacket;
    private static String tpsText;
    private static boolean healthyTps;

    private static int radarRefresh;
    private static int cooldownRefresh;
    private static int radarPlayers;
    private static int radarMobs;
    private static List<RadarDot> radarDots = List.of();
    private static KeyMapping[] knownMappings;

    private DashTelemetry() {
    }

    static void tick(Minecraft client, boolean radarEnabled, boolean cooldownsEnabled) {
        if (SaveMode.isPaused()) return;
        updateLevel(client.level);
        if (client.isPaused() && timeSamples != 0) {
            resetTimeSamples();
        }

        KeyMapping[] mappings = client.options.keyMappings;
        if (knownMappings != mappings) {
            knownMappings = mappings;
            DASH_KEYS.clear();
            for (KeyMapping mapping : mappings) {
                if (SaveMode.isManagedBinding(mapping)) {
                    DASH_KEYS.add(mapping);
                }
            }
        }

        LocalPlayer player = client.player;
        if (!radarEnabled || level == null || player == null) {
            clearRadar();
        } else if (--radarRefresh <= 0) {
            radarRefresh = 8;
            updateRadar(level, player);
        }

        if (!cooldownsEnabled || player == null) {
            cooldownRefresh = 0;
            COOLDOWNS.clear();
        } else if (--cooldownRefresh <= 0) {
            cooldownRefresh = 2;
            updateCooldowns(player);
        }
    }

    public static void onServerTime(Minecraft client, long gameTime) {
        if (SaveMode.isPaused()) return;
        updateLevel(client.level);
        if (level == null || client.isPaused() || level.tickRateManager().isFrozen()) {
            resetTimeSamples();
            return;
        }

        long now = System.nanoTime();
        if (timeSamples != 0) {
            int previous = (nextTimeSample + TIME_WINDOW - 1) % TIME_WINDOW;
            if (gameTime < GAME_TIMES[previous] || now - lastTimePacket > STALE_TIME_NANOS) {
                resetTimeSamples();
            } else if (gameTime == GAME_TIMES[previous]) {
                return;
            }
        }

        GAME_TIMES[nextTimeSample] = gameTime;
        RECEIVED_TIMES[nextTimeSample] = now;
        lastTimePacket = now;
        nextTimeSample = (nextTimeSample + 1) % TIME_WINDOW;
        timeSamples = Math.min(timeSamples + 1, TIME_WINDOW);

        if (timeSamples >= 2) {
            // A packet window averages delivery jitter without reading the client's simulated clock.
            int oldest = (nextTimeSample + TIME_WINDOW - timeSamples) % TIME_WINDOW;
            long elapsed = now - RECEIVED_TIMES[oldest];
            if (elapsed > 0) {
                float expected = level.tickRateManager().tickrate();
                double measured = (gameTime - GAME_TIMES[oldest]) * 1_000_000_000.0 / elapsed;
                tpsText = Double.toString(Math.round(Math.min(expected, measured) * 10) / 10.0);
                healthyTps = measured >= expected * 0.9;
            }
        }
    }

    private static void updateLevel(ClientLevel currentLevel) {
        if (level != currentLevel) {
            reset();
            level = currentLevel;
        }
    }

    static void reset() {
        level = null;
        resetTimeSamples();
        clearRadar();
        cooldownRefresh = 0;
        COOLDOWNS.clear();
        COOLDOWN_GROUPS.clear();
    }

    private static void resetTimeSamples() {
        timeSamples = 0;
        nextTimeSample = 0;
        lastTimePacket = 0;
        tpsText = null;
        healthyTps = false;
    }

    static int drawPing(GuiGraphics graphics, Minecraft client, int x, int y) {
        ClientPacketListener connection = client.getConnection();
        PlayerInfo info = connection != null && client.player != null
                ? connection.getPlayerInfo(client.player.getUUID())
                : null;
        String label = text("Пінг", "Пинг", "Ping");
        if (info == null || info.getLatency() < 0) {
            return emptyMetric(graphics, client.font, x, y, label, "--");
        }
        return metric(graphics, client.font, x, y, label, info.getLatency() + text(" мс", " мс", " ms"),
                HudSettings.secondary());
    }

    static int drawTps(GuiGraphics graphics, Minecraft client, int x, int y) {
        if (client.level != null && client.level.tickRateManager().isFrozen()) {
            return metric(graphics, client.font, x, y, "TPS", text("Пауза", "Пауза", "Paused"), DashHud.mutedColor());
        }
        if (tpsText == null || level != client.level || client.isPaused()
                || System.nanoTime() - lastTimePacket > STALE_TIME_NANOS) {
            return emptyMetric(graphics, client.font, x, y, "TPS", "--");
        }
        return metric(graphics, client.font, x, y, "TPS ~", tpsText,
                healthyTps ? HudSettings.secondary() : 0xFFF1BE88);
    }

    private static void clearRadar() {
        radarRefresh = 0;
        radarPlayers = 0;
        radarMobs = 0;
        radarDots = List.of();
    }

    private static void updateRadar(ClientLevel world, LocalPlayer player) {
        List<RadarDot> contacts = new ArrayList<>();
        radarPlayers = 0;
        radarMobs = 0;
        for (LivingEntity entity : world.getEntitiesOfClass(
                LivingEntity.class,
                player.getBoundingBox().inflate(RADAR_RANGE),
                entity -> entity != player && entity.isAlive() && !entity.isInvisibleTo(player)
                        && (entity instanceof Player || entity instanceof Mob))) {
            double distanceSquared = player.distanceToSqr(entity);
            if (distanceSquared > RADAR_RANGE * RADAR_RANGE) {
                continue;
            }

            boolean isPlayer = entity instanceof Player;
            boolean friend = false;
            if (entity instanceof Player contact) {
                radarPlayers++;
                friend = Friends.isFriend(contact.getGameProfile().name());
            } else {
                radarMobs++;
            }
            int dx = (int) Math.round((entity.getX() - player.getX()) * 9 / RADAR_RANGE);
            int dz = (int) Math.round((entity.getZ() - player.getZ()) * 9 / RADAR_RANGE);
            contacts.add(new RadarDot(dx, dz, distanceSquared, isPlayer, friend));
        }
        contacts.sort(Comparator.comparingDouble(RadarDot::distanceSquared));
        radarDots = List.copyOf(contacts.subList(0, Math.min(contacts.size(), MAX_RADAR_DOTS)));
    }

    static int drawRadar(GuiGraphics graphics, Minecraft client, int x, int y) {
        String title = text("Радар", "Радар", "Radar");
        if (level != client.level || radarPlayers + radarMobs == 0) {
            return emptyMetric(graphics, client.font, x, y, title, text("Порожньо", "Пусто", "Empty"));
        }

        DashHud.panel(graphics, x, y, x + WIDTH, y + 31);
        int centerX = x + 17;
        int centerY = y + 15;
        DashShape.frame(graphics, x + 6, y + 4, x + 29, y + 27, 5,
                HudSettings.color(HudSettings.shade(.29F)), HudSettings.color(HudSettings.shade(.12F)));
        int axisColor = HudSettings.color(HudSettings.shade(.23F));
        graphics.fill(centerX, y + 5, centerX + 1, y + 26, axisColor);
        graphics.fill(x + 7, centerY, x + 28, centerY + 1, axisColor);
        int playerColor = HudSettings.color(HudSettings.accent());
        int friendColor = HudSettings.color(Friends.COLOR);
        int mobColor = HudSettings.color(DashHud.mutedColor());
        for (int i = radarDots.size() - 1; i >= 0; i--) {
            RadarDot dot = radarDots.get(i);
            int dx = centerX + dot.dx();
            int dz = centerY + dot.dz();
            int color = dot.friend() ? friendColor : (dot.player() ? playerColor : mobColor);
            graphics.fill(dx, dz, dx + 2, dz + 2, color);
        }
        graphics.fill(centerX, centerY, centerX + 1, centerY + 1, HudSettings.color(HudSettings.secondary()));

        int right = x + WIDTH - 6;
        Font font = DashTypography.font(client.font);
        drawRow(graphics, font, x + 33, right, y + 3, title, RADAR_RANGE + text(" м", " м", " m"),
                HudSettings.secondary());
        drawRow(graphics, font, x + 33, right, y + 12, text("Гравці", "Игроки", "Players"),
                Integer.toString(radarPlayers), HudSettings.accent());
        drawRow(graphics, font, x + 33, right, y + 21, text("Моби", "Мобы", "Mobs"),
                Integer.toString(radarMobs), DashHud.mutedColor());
        return y + 33;
    }

    private static void updateCooldowns(LocalPlayer player) {
        COOLDOWNS.clear();
        COOLDOWN_GROUPS.clear();
        ItemCooldowns cooldowns = player.getCooldowns();
        collectCooldown(cooldowns, player.getMainHandItem());
        collectCooldown(cooldowns, player.getOffhandItem());

        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && COOLDOWNS.size() < MAX_COOLDOWNS; slot++) {
            collectCooldown(cooldowns, inventory.getItem(slot));
        }

        float attackRemaining = 1.0F - player.getAttackStrengthScale(0.0F);
        if (attackRemaining > 0.0F && COOLDOWNS.size() < MAX_COOLDOWNS) {
            COOLDOWNS.add(new Cooldown(Component.translatable("key.attack"), (int) Math.ceil(attackRemaining * 100)));
        }
    }

    private static void collectCooldown(ItemCooldowns cooldowns, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        float remaining = cooldowns.getCooldownPercent(stack, 0.0F);
        if (remaining > 0.0F && COOLDOWN_GROUPS.add(cooldowns.getCooldownGroup(stack))) {
            COOLDOWNS.add(new Cooldown(stack.getHoverName(), (int) Math.ceil(remaining * 100)));
        }
    }

    static int drawCooldowns(GuiGraphics graphics, Minecraft client, int x, int y) {
        Font font = DashTypography.font(client.font);
        String title = text("Кулдауни", "Откаты", "Cooldowns");
        if (level != client.level || COOLDOWNS.isEmpty()) {
            return emptyMetric(graphics, font, x, y, title, text("Готово", "Готово", "Ready"));
        }

        int bottom = y + 12 + COOLDOWNS.size() * 10;
        DashHud.panel(graphics, x, y, x + WIDTH, bottom);
        int titleColor = HudSettings.isObsidian() ? DashHud.textColor() : DashHud.mutedColor();
        graphics.drawString(font, title, x + 6, y + 3, HudSettings.textColor(titleColor), false);
        int rowY = y + 12;
        for (Cooldown cooldown : COOLDOWNS) {
            drawRow(graphics, font, x + 6, x + WIDTH - 6, rowY,
                    NameProtect.mask(cooldown.name()).getString(), cooldown.percent() + "%", HudSettings.secondary());
            rowY += 10;
        }
        return bottom + 2;
    }

    static int drawKeybinds(GuiGraphics graphics, Minecraft client, int x, int y) {
        Font font = DashTypography.font(client.font);
        int startY = y;
        for (KeyMapping key : DASH_KEYS) {
            if (key.isUnbound() && HudSettings.hideEmpty()) {
                continue;
            }
            String id = key.getName();
            String label = switch (id) {
                case "key.dash-client.open_menu" -> text("Меню", "Меню", "Menu");
                case "key.dash-client.freelook" -> text("Вільний огляд", "Свободный обзор", "Freelook");
                case "key.dash-client.trigger_bot" -> text("Тригер-бот", "Триггер-бот", "Trigger Bot");
                case "key.dash-client.zoom" -> text("Наближення", "Приближение", "Zoom");
                case "key.basefinder.toggle_esp" -> text("Пошук баз", "Поиск баз", "Base Finder");
                case "key.basefinder.toggle_freecam" -> text("Вільна камера", "Свободная камера", "Freecam");
                case "Toggle Nightvision" -> text("Нічний зір", "Ночное зрение", "Night Vision");
                default -> Component.translatableWithFallback(id,
                        id.substring(id.lastIndexOf('.') + 1).replace('_', ' ')).getString();
            };
            String value = key.isUnbound() ? text("Не задано", "Не задана", "Unbound")
                    : key.getTranslatedKeyMessage().getString();
            DashHud.panel(graphics, x, y, x + WIDTH, y + ROW_HEIGHT);
            drawRow(graphics, font, x + 6, x + WIDTH - 6, y + 3, label, value, HudSettings.accent());
            y += ROW_HEIGHT + 2;
        }
        return y != startY ? y : emptyMetric(graphics, font, x, y, text("Клавіші", "Клавиши", "Keys"),
                text("Немає", "Нет", "None"));
    }

    private static int emptyMetric(GuiGraphics graphics, Font font, int x, int y, String label, String value) {
        return HudSettings.hideEmpty() ? y : metric(graphics, font, x, y, label, value, DashHud.mutedColor());
    }

    private static int metric(GuiGraphics graphics, Font font, int x, int y, String label, String value, int color) {
        DashHud.drawMetric(graphics, font, x, y, label, value, color);
        return y + ROW_HEIGHT + 2;
    }

    private static void drawRow(
            GuiGraphics graphics, Font font, int x, int right, int y, String label, String value, int valueColor) {
        value = DashHud.fit(font, value, (right - x) * 2 / 3);
        int valueX = right - font.width(value);
        graphics.drawString(font, DashHud.fit(font, label, valueX - x - 4), x, y,
                HudSettings.textColor(DashHud.mutedColor()), false);
        graphics.drawString(font, value, valueX, y, HudSettings.textColor(DashHud.valueColor(valueColor)), false);
    }

    private record RadarDot(int dx, int dz, double distanceSquared, boolean player, boolean friend) {
    }

    private record Cooldown(Component name, int percent) {
    }
}
