package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import com.dash.client.DashClient;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

final class Marks {
    static final int MIN_SIZE = 50;
    static final int MAX_SIZE = 175;
    static final int MIN_OPACITY = 15;
    static final int MAX_OPACITY = 100;
    private static final int MAX_MARKS = 64;
    private static final int MAX_VISIBLE = 12;
    private static final double RANGE_SQUARED = 1024.0 * 1024.0;
    private static final int CRYSTAL_RADIUS = 7;
    private static final double MAX_GLOW_RADIUS = (CRYSTAL_RADIUS + 7) * Math.sqrt(2.0);
    private static final int DEFAULT_COLOR = 0x8BD9FF;
    private static final int DEFAULT_SIZE = 100;
    private static final int DEFAULT_OPACITY = 85;
    private static final Gson GSON = new Gson();
    private static final Path CONFIG = InventoryBindSupport.configFile("dash-client/marks.properties");
    private static final Path WORLD_MARKS = CONFIG.getParent().resolve("marks");
    private static KeyMapping key;

    private static boolean enabled = true;
    private static int color = DEFAULT_COLOR;
    private static int size = DEFAULT_SIZE;
    private static int opacity = DEFAULT_OPACITY;
    private static ClientLevel activeLevel;
    private static Path activeFile;
    private static List<Mark> activeMarks;

    private Marks() {}

    static void register() {
        try {
            Properties config = InventoryBindSupport.load(CONFIG);
            enabled = !"false".equals(config.getProperty("enabled"));
            color = readInt(config, "color", DEFAULT_COLOR, 0, 0xFFFFFF);
            size = readInt(config, "size", DEFAULT_SIZE, MIN_SIZE, MAX_SIZE);
            opacity = readInt(config, "opacity", DEFAULT_OPACITY, MIN_OPACITY, MAX_OPACITY);
        } catch (IOException | IllegalArgumentException failure) {
            DashClient.LOGGER.warn("Could not read Marks settings", failure);
        }
        key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.dash-client.marks",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, KeyMapping.Category.MISC));
        DashBindInput.register(key, Marks::place);
        ClientTickEvents.END_CLIENT_TICK.register(Marks::tick);
        // Keep the world-anchored crystal visible through blocks by rendering on the HUD.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CROSSHAIR, DashClient.id("ice_marks"), Marks::render);
    }

    static KeyMapping key() { return key; }
    static boolean isEnabled() { return enabled; }
    static int color() { return color; }
    static int size() { return size; }
    static int opacity() { return opacity; }

    static void toggle() {
        if (!saveSettings(!enabled, color, size, opacity)) {
            message(Minecraft.getInstance(), text("Не вдалося зберегти налаштування.", "Не удалось сохранить настройки.",
                    "Could not save settings."));
        }
    }

    static boolean applyStyle(int newColor, int newSize, int newOpacity) {
        return saveSettings(enabled, newColor & 0xFFFFFF, Math.clamp(newSize, MIN_SIZE, MAX_SIZE),
                Math.clamp(newOpacity, MIN_OPACITY, MAX_OPACITY));
    }

    private static boolean saveSettings(boolean nextEnabled, int nextColor, int nextSize, int nextOpacity) {
        var config = new Properties();
        config.setProperty("enabled", Boolean.toString(nextEnabled));
        config.setProperty("color", Integer.toString(nextColor));
        config.setProperty("size", Integer.toString(nextSize));
        config.setProperty("opacity", Integer.toString(nextOpacity));
        try {
            InventoryBindSupport.save(CONFIG, config, "Dash Marks");
            enabled = nextEnabled;
            color = nextColor;
            size = nextSize;
            opacity = nextOpacity;
            return true;
        } catch (IOException failure) {
            DashClient.LOGGER.warn("Could not save Marks settings", failure);
            return false;
        }
    }

    private static int readInt(Properties config, String key, int fallback, int min, int max) {
        try {
            return Math.clamp(Integer.parseInt(config.getProperty(key)), min, max);
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    static int count(Minecraft client) {
        ensureWorld(client);
        return activeMarks == null ? 0 : activeMarks.size();
    }

    static boolean removeLast(Minecraft client) {
        ensureWorld(client);
        if (activeMarks == null || activeMarks.isEmpty()) return false;
        return saveMarks(activeMarks.subList(0, activeMarks.size() - 1));
    }

    static boolean clear(Minecraft client) {
        ensureWorld(client);
        return activeMarks != null && !activeMarks.isEmpty() && saveMarks(List.of());
    }

    private static void tick(Minecraft client) {
        if (SaveMode.isPaused()) return;
        if (client.level == null || enabled) ensureWorld(client);
    }

    private static boolean place(Minecraft client) {
        if (!enabled) return false;
        ensureWorld(client);
        if (activeMarks == null) {
            message(client, text("Мітки цього світу недоступні.", "Метки этого мира недоступны.", "Marks for this world are unavailable."));
            return false;
        }
        Vec3 position = client.player.position();
        int existing = findMark(client, position);
        if (existing >= 0) {
            List<Mark> updated = new ArrayList<>(activeMarks);
            updated.remove(existing);
            if (saveMarks(updated)) {
                message(client, text("Мітку видалено.", "Метка удалена.", "Mark removed."));
                return true;
            }
            message(client, text("Не вдалося видалити мітку.", "Не удалось удалить метку.", "Could not remove mark."));
            return false;
        }
        if (activeMarks.size() >= MAX_MARKS) {
            message(client, text("Ліміт міток у цьому вимірі: 64.", "Лимит меток в этом измерении: 64.", "Limit in this dimension: 64 marks."));
            return false;
        }
        List<Mark> updated = new ArrayList<>(activeMarks);
        updated.add(new Mark(position));
        if (saveMarks(updated)) {
            message(client, text("Крижану мітку встановлено.", "Ледяная метка установлена.", "Ice mark placed."));
            return true;
        }
        message(client, text("Не вдалося зберегти мітку.", "Не удалось сохранить метку.", "Could not save mark."));
        return false;
    }

    private static int findMark(Minecraft client, Vec3 position) {
        var camera = client.gameRenderer.getMainCamera();
        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();
        boolean worldVisible = camera.isInitialized() && !client.options.hideGui;
        int visible = 0;
        double nearest = Double.POSITIVE_INFINITY;
        int aimed = -1;
        int atFeet = -1;
        for (int i = activeMarks.size() - 1; i >= 0; i--) {
            Mark mark = activeMarks.get(i);
            double distanceSquared = position.distanceToSqr(mark.position);
            if (atFeet < 0 && distanceSquared <= .04) atFeet = i;
            if (!worldVisible || visible >= MAX_VISIBLE || distanceSquared > RANGE_SQUARED) continue;
            Vec3 point = DashWaypoints.projectAnchor(client, camera, mark.anchor);
            if (point == null) continue;
            visible++;
            double offset = (Math.abs(point.x) * width + Math.abs(point.y) * height) * .5;
            float radius = crystalRadius(client, camera, mark.anchor, point, height);
            if (radius > 0.0F && offset <= radius && distanceSquared < nearest) {
                nearest = distanceSquared;
                aimed = i;
            }
        }
        return aimed >= 0 ? aimed : atFeet;
    }

    static void clearTransient() {
        activeLevel = null;
        activeFile = null;
        activeMarks = null;
    }

    private static void ensureWorld(Minecraft client) {
        if (activeLevel == client.level) return;
        clearTransient();
        if (client.level == null) return;
        String scope = DashWaypoints.worldScope(client);
        if (scope == null) return;
        activeLevel = client.level;
        activeFile = DashWaypoints.profileFile(WORLD_MARKS, scope, activeLevel.dimension().identifier().toString());
        try (var reader = Files.newBufferedReader(activeFile)) {
            Coordinates[] stored = GSON.fromJson(reader, Coordinates[].class);
            if (stored == null || stored.length > MAX_MARKS) throw new IOException("Invalid saved mark list");
            List<Mark> loaded = new ArrayList<>(stored.length);
            for (Coordinates point : stored) {
                if (point == null || !point.valid()) throw new IOException("Invalid saved mark position");
                loaded.add(new Mark(new Vec3(point.x(), point.y(), point.z())));
            }
            activeMarks = List.copyOf(loaded);
        } catch (NoSuchFileException missing) {
            activeMarks = List.of();
        } catch (IOException | JsonParseException failure) {
            DashClient.LOGGER.warn("Could not read ice marks for the current world", failure);
        }
    }

    private static boolean saveMarks(List<Mark> updated) {
        Coordinates[] stored = updated.stream().map(Mark::coordinates).toArray(Coordinates[]::new);
        try {
            DashWaypoints.writeJson(activeFile, GSON.toJson(stored));
            activeMarks = List.copyOf(updated);
            return true;
        } catch (IOException failure) {
            DashClient.LOGGER.warn("Could not save ice marks for the current world", failure);
            return false;
        }
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        if (!enabled || SaveMode.isPaused() || activeMarks == null || activeMarks.isEmpty()) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level != activeLevel || client.player == null || client.options.hideGui) return;
        var camera = client.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) return;

        float pulse = pulse();
        Vec3 player = client.player.position();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int drawn = 0;
        for (int i = activeMarks.size() - 1; i >= 0 && drawn < MAX_VISIBLE; i--) {
            Mark mark = activeMarks.get(i);
            double distanceSquared = player.distanceToSqr(mark.position);
            if (distanceSquared > RANGE_SQUARED) continue;
            Vec3 projected = DashWaypoints.projectAnchor(client, camera, mark.anchor);
            if (projected == null) continue;
            float radius = crystalRadius(client, camera, mark.anchor, projected, height);
            if (!(radius > 0.0F)) continue;
            float x = (float) ((projected.x + 1.0) * width * .5);
            float y = (float) ((1.0 - projected.y) * height * .5);
            var pose = graphics.pose();
            pose.pushMatrix();
            try {
                pose.translate(x, y);
                pose.scale((float) (radius / MAX_GLOW_RADIUS));
                drawCrystal(graphics, 0, 0, CRYSTAL_RADIUS, color, opacity, pulse);
            } finally {
                pose.popMatrix();
            }
            float labelScale = Math.min(1.0F, radius / 12.0F);
            // The label stays readable while inspecting a distant mark.
            if (Math.abs(x - width * .5F) + Math.abs(y - height * .5F) <= radius) labelScale = 1.0F;
            if (labelScale >= .5F) {
                drawDistance(graphics, client, mark.distanceLabel(Math.round(Math.sqrt(distanceSquared))),
                        x, y, radius, labelScale);
            }
            drawn++;
        }
    }

    private static float crystalRadius(Minecraft client, Camera camera, Vec3 anchor, Vec3 center, int height) {
        var up = camera.upVector();
        double worldRadius = .25 * size / DEFAULT_SIZE;
        Vec3 top = client.gameRenderer.projectPointToScreen(anchor.add(up.x() * worldRadius,
                up.y() * worldRadius, up.z() * worldRadius));
        double projectedRadius = Math.abs(top.y - center.y) * height * .5;
        return Double.isFinite(projectedRadius) ? (float) projectedRadius : 0.0F;
    }

    private static void drawDistance(GuiGraphics graphics, Minecraft client, String label, float x, float y,
                                     float radius, float scale) {
        int labelWidth = client.font.width(label);
        float left = Math.clamp(x - labelWidth * scale * .5F, 3.0F,
                Math.max(3.0F, graphics.guiWidth() - labelWidth * scale - 3.0F));
        float top = y + radius + 2;
        if (top + 11 * scale > graphics.guiHeight()) top = y - radius - 13 * scale;
        var pose = graphics.pose();
        pose.pushMatrix();
        try {
            pose.translate(left, top);
            pose.scale(scale);
            graphics.fill(-3, -2, labelWidth + 3, 10, alpha(0x071725, opacity, .78F));
            graphics.drawString(client.font, label, 0, 0, alpha(0xF1FBFF, opacity, 1.0F), false);
        } finally {
            pose.popMatrix();
        }
    }

    static float pulse() {
        return .5F + .5F * (float) Math.sin((System.nanoTime() % 2_400_000_000L) * (Math.TAU / 2_400_000_000.0));
    }

    static int radius(int markerSize) {
        return Math.round((float) CRYSTAL_RADIUS * markerSize / DEFAULT_SIZE);
    }

    static void drawCrystal(GuiGraphics graphics, int x, int y, int radius, int pigment, int transparency, float pulse) {
        var pose = graphics.pose();
        pose.pushMatrix();
        try {
            pose.translate(x, y);
            pose.rotate((float) (Math.PI / 4.0));
            int ring = radius + 4 + Math.round(3.0F * pulse);
            graphics.fill(-ring, -ring, ring, ring, alpha(pigment, transparency, .11F + pulse * .06F));
            outline(graphics, ring, alpha(pigment, transparency, .42F - pulse * .18F));
            int inner = radius + 2;
            outline(graphics, inner, alpha(0xE8F8FF, transparency, .48F));
            graphics.fillGradient(-radius, -radius, radius, radius, alpha(0xF1FEFF, transparency, 1),
                    alpha(pigment, transparency, .96F));
            graphics.fill(-radius, -radius, 0, radius, alpha(pigment, transparency, .45F));
            graphics.fill(0, -radius, 1, radius, alpha(0xFFFFFF, transparency, .94F));
        } finally {
            pose.popMatrix();
        }
        int spark = alpha(0xE9FBFF, transparency, .38F + .52F * pulse);
        int reach = radius + 4;
        graphics.hLine(x + reach - 2, x + reach + 2, y - reach, spark);
        graphics.vLine(x + reach, y - reach - 2, y - reach + 2, spark);
        graphics.hLine(x - reach - 1, x - reach + 1, y + reach / 2, spark);
        graphics.vLine(x - reach, y + reach / 2 - 1, y + reach / 2 + 1, spark);
    }

    private static void outline(GuiGraphics graphics, int radius, int argb) {
        graphics.fill(-radius, -radius, radius, -radius + 1, argb);
        graphics.fill(-radius, radius - 1, radius, radius, argb);
        graphics.fill(-radius, -radius + 1, -radius + 1, radius - 1, argb);
        graphics.fill(radius - 1, -radius + 1, radius, radius - 1, argb);
    }

    private static int alpha(int rgb, int transparency, float layer) {
        return Math.round(2.55F * transparency * layer) << 24 | rgb;
    }

    private static void message(Minecraft client, String message) {
        client.gui.getChat().addMessage(Component.literal("[Marks] " + message));
    }

    private static final class Mark {
        private final Vec3 position;
        private final Vec3 anchor;
        private long shownDistance = -1;
        private String unit = "";
        private String label;

        Mark(Vec3 position) {
            this.position = position;
            anchor = position.add(0.0, .18, 0.0);
        }

        Coordinates coordinates() {
            return new Coordinates(position.x, position.y, position.z);
        }

        String distanceLabel(long distance) {
            String nextUnit = text(" бл.", " бл.", " blocks");
            if (distance != shownDistance || !unit.equals(nextUnit)) {
                shownDistance = distance;
                unit = nextUnit;
                label = distance + unit;
            }
            return label;
        }
    }

    private record Coordinates(double x, double y, double z) {
        boolean valid() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && Math.abs(x) <= 30_000_000 && Math.abs(z) <= 30_000_000;
        }
    }
}
