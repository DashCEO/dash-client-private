package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.StringDecomposer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

final class DashHud {
    static final int MUTED = 0xFFB0A5C4;

    private static final int WIDTH = 104;
    private static final int ROW_HEIGHT = 13;
    private static final int TEXT = 0xFFF2EDF9;
    private static final int TARGET_WIDTH = 150;
    private static final int TARGET_HEIGHT = 34;
    private static final int ACTIVITY_HEIGHT = 16;
    private static final EquipmentSlot[] TARGET_EQUIPMENT = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static UUID healthTarget;
    private static long healthTime;
    private static float displayedHealth;

    private DashHud() {
    }

    static void panel(GuiGraphics graphics, int x, int y, int right, int bottom) {
        int background = HudSettings.color(0xE5000000 | (HudSettings.shade(.04F) & 0xFFFFFF));
        DashShape.frame(graphics, x, y, right, bottom, 5, borderColor(), background);
    }

    private static int borderColor() {
        return HudSettings.color(0x72000000 | (HudSettings.shade(.42F) & 0xFFFFFF));
    }

    static int mutedColor() {
        return HudSettings.isObsidian() ? 0xFFB8C1CC : MUTED;
    }

    static int textColor() {
        return HudSettings.isObsidian() ? 0xFFF4F7FC : TEXT;
    }

    static int valueColor(int color) {
        if (color == MUTED) return mutedColor();
        return HudSettings.isObsidian() && color == HudSettings.secondary() ? textColor() : color;
    }

    static void drawBrand(GuiGraphics graphics, Font vanillaFont, int x, int y) {
        Font font = DashTypography.font(vanillaFont);
        panel(graphics, x, y, x + WIDTH, y + 20);
        int accent = HudSettings.color(HudSettings.accent());
        int secondary = HudSettings.color(HudSettings.isObsidian() ? HudSettings.accent() : HudSettings.secondary());
        int text = HudSettings.textColor(textColor());
        DashShape.fill(graphics, x + 6, y + 4, x + 18, y + 16, 3,
                HudSettings.color(HudSettings.shade(.22F)));
        graphics.fill(x + 9, y + 7, x + 15, y + 8, accent);
        graphics.fill(x + 8, y + 10, x + 16, y + 11, text);
        graphics.fill(x + 9, y + 13, x + 15, y + 14, secondary);
        graphics.drawString(font, "DASH CLIENT", x + 23, y + 6, text, false);
        DashShape.fill(graphics, x + 95, y + 8, x + 99, y + 12, 2, secondary);
    }

    static void drawMetric(GuiGraphics graphics, Font vanillaFont, int x, int y,
                           String label, String value, int valueTint) {
        Font font = DashTypography.font(vanillaFont);
        panel(graphics, x, y, x + WIDTH, y + ROW_HEIGHT);
        String compactValue = fit(font, value, (WIDTH - 12) * 2 / 3);
        int valueX = x + WIDTH - 6 - font.width(compactValue);
        graphics.drawString(font, fit(font, label, valueX - x - 10),
                x + 6, y + 3, HudSettings.textColor(mutedColor()), false);
        graphics.drawString(font, compactValue, valueX, y + 3,
                HudSettings.textColor(valueColor(valueTint)), false);
    }

    static void drawKeys(GuiGraphics graphics, Minecraft client, int x, int y) {
        Font font = DashTypography.font(client.font);
        panel(graphics, x, y, x + WIDTH, y + ROW_HEIGHT);
        graphics.drawString(font, text("Рух", "Движ.", "Move"), x + 6, y + 3,
                HudSettings.textColor(mutedColor()), false);
        drawKey(graphics, font, x + 44, y, "W", client.options.keyUp.isDown());
        drawKey(graphics, font, x + 58, y, "A", client.options.keyLeft.isDown());
        drawKey(graphics, font, x + 72, y, "S", client.options.keyDown.isDown());
        drawKey(graphics, font, x + 86, y, "D", client.options.keyRight.isDown());
    }

    private static void drawKey(GuiGraphics graphics, Font font, int x, int y, String label, boolean pressed) {
        int surface = pressed && HudSettings.isObsidian() ? 0xFF174554 : HudSettings.shade(pressed ? .34F : .10F);
        DashShape.fill(graphics, x, y + 2, x + 12, y + 11, 3, HudSettings.color(surface));
        graphics.drawString(font, label, x + (12 - font.width(label)) / 2, y + 3,
                HudSettings.textColor(pressed ? HudSettings.secondary() : mutedColor()), false);
    }

    static void drawTarget(GuiGraphics graphics, Minecraft client, LivingEntity target) {
        if (target == null) {
            healthTarget = null;
            if (HudSettings.hideEmpty()) return;
        }

        ItemStack activity = target == null ? ItemStack.EMPTY : TargetActivity.activeItem(target);
        boolean usingItem = !activity.isEmpty();
        if (target != null && !usingItem) {
            activity = TargetActivity.recentItem(target);
        }
        int cardWidth = target == null ? WIDTH : TARGET_WIDTH;
        int cardHeight = target == null ? ROW_HEIGHT : TARGET_HEIGHT + (activity.isEmpty() ? 0 : ACTIVITY_HEIGHT);
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        float scale = Math.min(HudSettings.scale(), Math.min(width * .44F / (cardWidth + 28.0F),
                (height - 36.0F) / cardHeight));
        if (scale <= 0.0F) return;

        float middleX = width * .5F;
        float middleY = height * .5F;
        float targetX = middleX;
        float targetY = middleY;
        if (target != null) {
            var camera = client.gameRenderer.getMainCamera();
            float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(
                    !target.level().tickRateManager().isEntityFrozen(target));
            Vec3 point = target.getPosition(partialTick)
                    .add(0.0, target.getBoundingBox().getYsize() * .5, 0.0);
            Vec3 relative = point.subtract(camera.position());
            var forward = camera.forwardVector();
            if (camera.isInitialized() && relative.x * forward.x() + relative.y * forward.y()
                    + relative.z * forward.z() > .05) {
                Vec3 projected = client.gameRenderer.projectPointToScreen(point);
                if (Double.isFinite(projected.x) && Double.isFinite(projected.y)) {
                    targetX = middleX * (1.0F + Mth.clamp((float) projected.x, -.28F, .28F));
                    targetY = middleY * (1.0F - Mth.clamp((float) projected.y, -.25F, .25F));
                }
            }
        }

        float gap = 28.0F * scale;
        float right = targetX + gap;
        float left = targetX - gap - cardWidth * scale;
        float rightLimit = width - cardWidth * scale - 6.0F;
        float x = Mth.clamp(right > rightLimit || (targetX > middleX + gap && left >= 6.0F) ? left : right,
                6.0F, rightLimit);
        float y = Mth.clamp(Math.max(middleY, targetY) + 24.0F * scale,
                6.0F, height - cardHeight * scale - 30.0F);

        if (!DashHudLayout.beginProjected(graphics, DashHudLayout.Widget.TARGET, x, y, cardWidth, cardHeight, scale)) {
            return;
        }
        try {
            if (target == null) {
                drawMetric(graphics, client.font, 0, 0, text("Ціль", "Цель", "Target"),
                        text("Немає", "Нет", "None"), MUTED);
                return;
            }
            drawTargetCard(graphics, DashTypography.font(client.font), target, cardHeight, activity, usingItem);
        } finally {
            DashHudLayout.end(graphics);
        }
    }

    private static void drawTargetCard(GuiGraphics graphics, Font font, LivingEntity target, int height,
                                       ItemStack activity, boolean usingItem) {
        boolean obsidian = HudSettings.isObsidian();
        int background = HudSettings.color(obsidian
                ? 0xE7000000 | (HudSettings.shade(.04F) & 0xFFFFFF) : 0xE7101620);
        if (obsidian) {
            DashShape.frame(graphics, 0, 0, TARGET_WIDTH, height, 7, borderColor(), background);
        } else {
            DashShape.fill(graphics, 0, 0, TARGET_WIDTH, height, 7, background);
        }

        String name = StringDecomposer.getPlainText(NameProtect.mask(target.getName()));
        if (target instanceof AbstractClientPlayer player) {
            PlayerFaceRenderer.draw(graphics, player.getSkin(), 6, 6, 22, HudSettings.color(0xFFFFFFFF));
        } else {
            String initial = name.isBlank() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1));
            DashShape.fill(graphics, 6, 6, 28, 28, 4, HudSettings.color(HudSettings.shade(.20F)));
            graphics.drawString(font, initial, 17 - font.width(initial) / 2, 13,
                    HudSettings.color(HudSettings.accent()), false);
        }
        float health = Math.max(0.0F, target.getHealth());
        int tenths = Math.round(health * 10);
        String amount = tenths % 10 == 0 ? Integer.toString(tenths / 10) : Float.toString(tenths / 10.0F);
        String hp = fit(font, amount + " HP", 60);
        int hpX = TARGET_WIDTH - 7 - font.width(hp);
        graphics.drawString(font, fit(font, name, hpX - 38), 34, 6, HudSettings.textColor(textColor()), false);
        graphics.drawString(font, hp, hpX, 6, HudSettings.textColor(textColor()), false);

        int gearX = 34;
        for (EquipmentSlot slot : TARGET_EQUIPMENT) {
            ItemStack stack = target.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                drawSmallItem(graphics, target, stack, gearX, 17);
                gearX += 13;
            }
        }
        drawHealthBar(graphics, target, health);

        if (!activity.isEmpty()) {
            int divider = obsidian ? HudSettings.shade(.32F) : 0xFF394450;
            graphics.fill(6, TARGET_HEIGHT, TARGET_WIDTH - 6, TARGET_HEIGHT + 1, HudSettings.color(divider));
            drawSmallItem(graphics, target, activity, 7, TARGET_HEIGHT + 2);
            String label = usingItem ? text("Використовує", "Использует", "Using") : text("Щойно", "Недавно", "Recent");
            int nameX = 23 + font.width(label);
            String itemName = StringDecomposer.getPlainText(NameProtect.mask(activity.getHoverName()));
            graphics.drawString(font, label, 19, TARGET_HEIGHT + 5, HudSettings.textColor(HudSettings.accent()), false);
            graphics.drawString(font, fit(font, itemName, TARGET_WIDTH - nameX - 6), nameX, TARGET_HEIGHT + 5,
                    HudSettings.textColor(textColor()), false);
        }
    }

    private static void drawSmallItem(GuiGraphics graphics, LivingEntity target, ItemStack stack, int x, int y) {
        var pose = graphics.pose();
        pose.pushMatrix();
        try {
            pose.translate(x, y).scale(.6875F);
            graphics.renderItem(target, stack, 0, 0, 0);
        } finally {
            pose.popMatrix();
        }
    }

    private static void drawHealthBar(GuiGraphics graphics, LivingEntity target, float health) {
        int left = 34;
        int right = TARGET_WIDTH - 7;
        float fraction = Mth.clamp(health / Math.max(1.0F, target.getMaxHealth()), 0.0F, 1.0F);
        int fill = Math.round((right - left) * smoothHealth(target.getUUID(), fraction));
        boolean obsidian = HudSettings.isObsidian();
        int trackColor = HudSettings.color(obsidian ? HudSettings.shade(.33F) : 0xFF394450);
        int goodHealth = obsidian ? HudSettings.accent() : HudSettings.secondary();
        int healthColor = HudSettings.color(fraction > 0.25F ? goodHealth : 0xFFFF826E);
        DashShape.fill(graphics, left, 29, right, 32, 1, trackColor);
        DashShape.fill(graphics, left, 29, left + fill, 32, 1, healthColor);
    }

    static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        int ellipsisWidth = font.width("…");
        return width < ellipsisWidth ? "" : font.plainSubstrByWidth(text, width - ellipsisWidth) + "…";
    }

    private static float smoothHealth(UUID target, float fraction) {
        long now = System.nanoTime();
        if (target.equals(healthTarget)) {
            float blend = Math.min(1.0F, (now - healthTime) / 110_000_000.0F);
            displayedHealth += (fraction - displayedHealth) * blend;
        } else {
            healthTarget = target;
            displayedHealth = fraction;
        }
        healthTime = now;
        return displayedHealth;
    }
}
