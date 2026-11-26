package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

public final class DashHudLayout {
    static final float MIN_SIZE = .65F;
    static final float MAX_SIZE = 1.75F;
    static final float MIN_OPACITY = .25F;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String KEY = "hudLayout.";
    private static final int MARGIN = 5;
    private static final int METRIC_WIDTH = 104;
    private static final int METRIC_HEIGHT = 13;
    private static final long CLICK_WINDOW = 1_000_000_000L;
    private static final long NOTICE_DURATION = 3_500_000_000L;
    private static final Widget[] WIDGETS = Widget.values();
    private static final Entry[] ENTRIES = new Entry[WIDGETS.length];
    private static final RuntimeBounds[] BOUNDS = new RuntimeBounds[WIDGETS.length];
    private static final long[] CLICKS = new long[128];
    private static final Notice[] NOTICES = new Notice[4];
    private static final String[] ACTIVE_NAMES = new String[8];
    private static List<DashClientClient.DashboardModule> moduleList = List.of();
    private static boolean[] moduleStates = {};
    private static int clickHead;
    private static int clickCount;
    private static int sampledClicks = -1;
    private static int activeCount;
    private static int visibleActiveCount;
    private static int noticeCount;
    private static int lastX;
    private static int lastY;
    private static int lastZ;
    private static String coordinates = "";
    private static String rate = "0";
    private static String activeOverflow = "";
    private static String waypoint;
    private static Object world;
    private static boolean worldReady;
    private static boolean dirty;
    private static float widgetOpacity = 1;
    private static int widgetAccent;

    static {
        for (Widget widget : WIDGETS) {
            ENTRIES[widget.ordinal()] = new Entry();
            BOUNDS[widget.ordinal()] = new RuntimeBounds();
        }
    }

    enum Widget {
        WATERMARK("Логотип", "Логотип", "Watermark", 104, 20, .00F, .00F),
        FPS("FPS", "FPS", "FPS", 104, 13, .00F, .08F),
        PING("Пінг", "Пинг", "Ping", 104, 13, 1.00F, .00F),
        COORDINATES("Координати", "Координаты", "Coordinates", 166, 13, .00F, .91F),
        CPS("Кліки/с", "Клики/с", "CPS", 104, 13, .00F, .97F),
        KEYSTROKES("Клавіші руху", "Клавиши движения", "Keystrokes", 104, 13, .00F, .14F),
        ARMOR("Броня", "Броня", "Armor", 104, 40, .00F, .26F),
        POTIONS("Ефекти зілля", "Эффекты зелий", "Potions", 104, 28, .00F, .48F),
        NOTIFICATIONS("Повідомлення", "Уведомления", "Notifications", 164, 24, .50F, .00F),
        TARGET("Панель цілі", "Панель цели", "Target HUD", 150, 34, .55F, .63F),
        ACTIVE_MODULES("Активні модулі", "Активные модули", "Active modules", 104, 45, 1.00F, .45F),
        WAYPOINTS("Мітки маршруту", "Точки маршрута", "Waypoints", 104, 13, .00F, .85F),
        STAFF("Модератори", "Модераторы", "StaffList", 104, 24, 1.00F, .36F),
        KEYBINDS("Гарячі клавіші", "Горячие клавиши", "Keybinds", 104, 24, 1.00F, .23F),
        RADAR("Радар", "Радар", "Radar", 104, 31, 1.00F, .14F),
        SERVER_TPS("TPS сервера", "TPS сервера", "Server TPS", 104, 13, 1.00F, .07F),
        COOLDOWNS("Перезарядка", "Перезарядки", "Cooldowns", 104, 13, 1.00F, .30F),
        TOTEMS("Тотеми", "Тотемы", "Totems", 104, 13, .00F, .58F),
        HIT_FEEDBACK("Останній удар", "Последний удар", "Hit feedback", 104, 30, .00F, .68F);

        final String uk;
        final String ru;
        final String en;
        final int width;
        final int height;
        final float x;
        final float y;
        final String prefix;

        Widget(String uk, String ru, String en, int width, int height, float x, float y) {
            this.uk = uk;
            this.ru = ru;
            this.en = en;
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
            prefix = KEY + name().toLowerCase(Locale.ROOT) + ".";
        }

        String label() { return text(uk, ru, en); }

        boolean usesLegacyFeature() {
            return switch (this) {
                case ACTIVE_MODULES, CPS, COORDINATES, NOTIFICATIONS -> false;
                default -> true;
            };
        }
    }

    private static final class Entry {
        private float x = Float.NaN;
        private float y = Float.NaN;
        private float size = 1;
        private float opacity = 1;
        private int accent;
        private int visibility = -1;
    }

    private static final class RuntimeBounds {
        private int width;
        private int height;
        private float scale;
        private float x = Float.NaN;
        private float y = Float.NaN;
    }

    private record Notice(String title, String detail, long started) {}

    private DashHudLayout() {}

    static boolean enabled(Widget widget) {
        if (widget.usesLegacyFeature()) return DashClientClient.hudWidgetFeatureEnabled(widget);
        int visibility = entry(widget).visibility;
        return visibility >= 0 ? visibility == 1 : widget == Widget.COORDINATES;
    }

    static void toggle(Widget widget) {
        boolean value = !enabled(widget);
        entry(widget).visibility = value ? 1 : 0;
        DashClientClient.setHudWidgetEnabled(widget, value);
        dirty = true;
        save();
    }

    static float size(Widget widget) { return entry(widget).size; }
    static float opacity(Widget widget) { return entry(widget).opacity; }

    static int accent(Widget widget) { return entry(widget).accent; }
    static int accentOr(int defaultColor) { return widgetAccent == 0 ? defaultColor : widgetAccent; }
    static float opacityMultiplier() { return widgetOpacity; }

    static void size(Widget widget, float value) {
        entry(widget).size = Mth.clamp(value, MIN_SIZE, MAX_SIZE);
        dirty = true;
    }

    static void opacity(Widget widget, float value) {
        entry(widget).opacity = Mth.clamp(value, MIN_OPACITY, 1);
        dirty = true;
    }

    static void accent(Widget widget, int value) {
        entry(widget).accent = value == 0 ? 0 : value | 0xFF000000;
        dirty = true;
    }

    static void reset(Widget widget) {
        var entry = entry(widget);
        entry.x = Float.NaN;
        entry.y = Float.NaN;
        entry.size = 1;
        entry.opacity = 1;
        entry.accent = 0;
        dirty = true;
        save();
    }

    static void resetPositions() {
        for (var entry : ENTRIES) {
            entry.x = Float.NaN;
            entry.y = Float.NaN;
        }
        dirty = true;
        save();
    }

    static void move(Widget widget, float x, float y, int width, int height) {
        var entry = entry(widget);
        entry.x = normalize(x - MARGIN, width - displayedWidth(widget, width, height) - 2 * MARGIN);
        entry.y = normalize(y - MARGIN, height - displayedHeight(widget, width, height) - 2 * MARGIN);
        dirty = true;
    }

    static boolean begin(GuiGraphics graphics, Widget widget, int x, int y, int w, int h) {
        return begin(graphics, widget, x, y, w, h, 1);
    }

    static boolean begin(GuiGraphics graphics, Widget widget, int x, int y, int w, int h, float baseScale) {
        return place(graphics, widget, x * baseScale, y * baseScale, w, h, baseScale, baseScale, x, y);
    }

    static boolean beginProjected(GuiGraphics graphics, Widget widget, float x, float y, int w, int h, float baseScale) {
        return place(graphics, widget, x, y, w, h, baseScale, 1, 0, 0);
    }

    private static boolean place(GuiGraphics graphics, Widget widget, float fallbackX, float fallbackY,
                                 int w, int h, float baseScale, float parentScale, int sourceX, int sourceY) {
        if (!enabled(widget) || baseScale <= 0 || parentScale <= 0) return false;
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        var entry = entry(widget);
        var bounds = bounds(widget);
        bounds.width = w;
        bounds.height = h;
        bounds.scale = baseScale;
        bounds.x = fallbackX;
        bounds.y = fallbackY;
        float scale = displayedScale(entry, bounds, width, height);
        int guiScale = (int) Minecraft.getInstance().getWindow().getGuiScale();
        float x = DashTypography.snap(positionX(widget, width, height), guiScale);
        float y = DashTypography.snap(positionY(widget, width, height), guiScale);
        graphics.pose().pushMatrix();
        graphics.pose().translate(x / parentScale, y / parentScale).scale(scale / parentScale).translate(-sourceX, -sourceY);
        widgetOpacity = entry.opacity;
        widgetAccent = entry.accent;
        return true;
    }

    static void end(GuiGraphics graphics) {
        graphics.pose().popMatrix();
        widgetOpacity = 1;
        widgetAccent = 0;
    }

    static float displayedWidth(Widget widget, int width, int height) {
        var bounds = bounds(widget);
        return (bounds.width == 0 ? widget.width : bounds.width) * displayedScale(entry(widget), bounds, width, height);
    }

    static float displayedHeight(Widget widget, int width, int height) {
        var bounds = bounds(widget);
        return (bounds.height == 0 ? widget.height : bounds.height) * displayedScale(entry(widget), bounds, width, height);
    }

    static float positionX(Widget widget, int width, int height) {
        float span = Math.max(0, width - 2 * MARGIN - displayedWidth(widget, width, height));
        float custom = entry(widget).x;
        float fallback = bounds(widget).x;
        if (Float.isFinite(custom)) return MARGIN + custom * span;
        if (Float.isFinite(fallback)) return Mth.clamp(fallback, MARGIN, MARGIN + span);
        return MARGIN + widget.x * span;
    }

    static float positionY(Widget widget, int width, int height) {
        float span = Math.max(0, height - 2 * MARGIN - displayedHeight(widget, width, height));
        float custom = entry(widget).y;
        float fallback = bounds(widget).y;
        if (Float.isFinite(custom)) return MARGIN + custom * span;
        if (Float.isFinite(fallback)) return Mth.clamp(fallback, MARGIN, MARGIN + span);
        return MARGIN + widget.y * span;
    }

    private static float displayedScale(Entry entry, RuntimeBounds bounds, int width, int height) {
        float base = bounds.scale > 0 ? bounds.scale : HudSettings.scale();
        float maxX = (width - 2F * MARGIN) / Math.max(1, bounds.width);
        float maxY = (height - 2F * MARGIN) / Math.max(1, bounds.height);
        return Math.min(base * entry.size, Math.max(.05F, Math.min(maxX, maxY)));
    }

    private static float normalize(float position, float span) {
        return span > 0 ? Mth.clamp(position / span, 0, 1) : 0;
    }

    private static Entry entry(Widget widget) { return ENTRIES[widget.ordinal()]; }
    private static RuntimeBounds bounds(Widget widget) { return BOUNDS[widget.ordinal()]; }
    private static Path file() { return InventoryBindSupport.configFile("dash-hud-layout.properties"); }

    static void load() {
        try {
            read(InventoryBindSupport.load(file()));
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.warn("Could not read the Dash HUD layout", exception);
        }
    }

    static void save() {
        if (!dirty) return;
        var properties = new Properties();
        capture(properties);
        try {
            InventoryBindSupport.save(file(), properties, "Dash HUD layout");
            dirty = false;
        } catch (IOException exception) {
            LOGGER.warn("Could not save the Dash HUD layout", exception);
        }
    }

    static void capture(Properties properties) {
        properties.setProperty(KEY + "version", "1");
        for (Widget widget : WIDGETS) {
            var entry = entry(widget);
            if (Float.isFinite(entry.x)) properties.setProperty(widget.prefix + "x", Float.toString(entry.x));
            if (Float.isFinite(entry.y)) properties.setProperty(widget.prefix + "y", Float.toString(entry.y));
            properties.setProperty(widget.prefix + "size", Float.toString(entry.size));
            properties.setProperty(widget.prefix + "opacity", Float.toString(entry.opacity));
            properties.setProperty(widget.prefix + "accent", Integer.toUnsignedString(entry.accent));
            if (entry.visibility >= 0) properties.setProperty(widget.prefix + "visible", Boolean.toString(enabled(widget)));
        }
    }

    static void apply(Properties properties) {
        if (read(properties)) {
            dirty = true;
            save();
        }
    }

    private static boolean read(Properties properties) {
        if (!"1".equals(properties.getProperty(KEY + "version"))) return false;
        for (Widget widget : WIDGETS) {
            var entry = entry(widget);
            entry.x = readFloat(properties, widget.prefix + "x", Float.NaN, 0, 1);
            entry.y = readFloat(properties, widget.prefix + "y", Float.NaN, 0, 1);
            entry.size = readFloat(properties, widget.prefix + "size", 1, MIN_SIZE, MAX_SIZE);
            entry.opacity = readFloat(properties, widget.prefix + "opacity", 1, MIN_OPACITY, 1);
            entry.accent = readColor(properties.getProperty(widget.prefix + "accent", "0"));
            entry.visibility = switch (properties.getProperty(widget.prefix + "visible", "")) {
                case "true" -> 1;
                case "false" -> 0;
                default -> -1;
            };
            if (entry.visibility >= 0) DashClientClient.setHudWidgetEnabled(widget, entry.visibility == 1);
        }
        return true;
    }

    private static float readFloat(Properties properties, String key, float fallback, float min, float max) {
        String text = properties.getProperty(key);
        if (text == null) return fallback;
        try {
            float value = Float.parseFloat(text);
            return Float.isFinite(value) ? Mth.clamp(value, min, max) : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int readColor(String text) {
        try {
            int value = Integer.parseUnsignedInt(text);
            return value == 0 ? 0 : value | 0xFF000000;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public static void recordLeftClick(Minecraft client) {
        if (client.screen != null || client.level == null || SaveMode.isPaused() || !enabled(Widget.CPS)) return;
        long now = System.nanoTime();
        expireClicks(now);
        if (clickCount == CLICKS.length) {
            clickHead = (clickHead + 1) % CLICKS.length;
            clickCount--;
        }
        CLICKS[(clickHead + clickCount) % CLICKS.length] = now;
        clickCount++;
    }

    private static void expireClicks(long now) {
        while (clickCount > 0 && now - CLICKS[clickHead] >= CLICK_WINDOW) {
            clickHead = (clickHead + 1) % CLICKS.length;
            clickCount--;
        }
    }

    static void notify(String title, String detail) {
        if (!enabled(Widget.NOTIFICATIONS) || SaveMode.isPaused()) return;
        if (noticeCount == NOTICES.length) {
            System.arraycopy(NOTICES, 1, NOTICES, 0, --noticeCount);
        }
        NOTICES[noticeCount++] = new Notice(title, detail, System.nanoTime());
    }

    static void tick(Minecraft client) {
        if (SaveMode.isPaused()) return;
        if (client.level == null || client.player == null) {
            if (worldReady) clearTransient();
            return;
        }
        boolean changedWorld = world != client.level;
        if (changedWorld) {
            world = client.level;
            clickCount = 0;
            clearNotices();
        }
        long now = System.nanoTime();
        expireClicks(now);
        if (enabled(Widget.CPS) && sampledClicks != clickCount) {
            sampledClicks = clickCount;
            rate = Integer.toString(clickCount);
        }
        if (enabled(Widget.COORDINATES)) {
            var pos = client.player.blockPosition();
            if (coordinates.isEmpty() || lastX != pos.getX() || lastY != pos.getY() || lastZ != pos.getZ()) {
                lastX = pos.getX();
                lastY = pos.getY();
                lastZ = pos.getZ();
                coordinates = lastX + " / " + lastY + " / " + lastZ;
            }
        }
        waypoint = enabled(Widget.WAYPOINTS) ? DashWaypoints.hudSummary(client) : null;
        int expired = 0;
        while (expired < noticeCount && now - NOTICES[expired].started() >= NOTICE_DURATION) expired++;
        if (expired > 0) {
            int oldCount = noticeCount;
            noticeCount -= expired;
            System.arraycopy(NOTICES, expired, NOTICES, 0, noticeCount);
            Arrays.fill(NOTICES, noticeCount, oldCount, null);
        }
        updateModuleStates(changedWorld || !worldReady);
        worldReady = true;
    }

    static void clearTransient() {
        clickHead = 0;
        clickCount = 0;
        sampledClicks = -1;
        rate = "0";
        Arrays.fill(ACTIVE_NAMES, null);
        activeCount = 0;
        visibleActiveCount = 0;
        activeOverflow = "";
        clearNotices();
        coordinates = "";
        waypoint = null;
        world = null;
        worldReady = false;
    }

    private static void clearNotices() {
        Arrays.fill(NOTICES, 0, noticeCount, null);
        noticeCount = 0;
    }

    private static void updateModuleStates(boolean initial) {
        if (!enabled(Widget.ACTIVE_MODULES) && !enabled(Widget.NOTIFICATIONS)) return;
        if (moduleList.isEmpty()) moduleList = DashClientClient.dashboardModules();
        if (moduleStates.length != moduleList.size()) {
            moduleStates = new boolean[moduleList.size()];
            initial = true;
        }
        int oldOverflow = activeCount - visibleActiveCount;
        activeCount = 0;
        visibleActiveCount = 0;
        for (int i = 0; i < moduleStates.length; i++) {
            var module = moduleList.get(i);
            if (module.category().equals("Client")) continue;
            boolean on = module.enabled().getAsBoolean();
            if (on) {
                activeCount++;
                if (visibleActiveCount < ACTIVE_NAMES.length) ACTIVE_NAMES[visibleActiveCount++] = module.label().get();
            }
            if (!initial && on != moduleStates[i]) {
                notify(module.label().get(), on ? text("Увімкнено", "Включено", "Enabled")
                        : text("Вимкнено", "Выключено", "Disabled"));
            }
            moduleStates[i] = on;
        }
        int overflow = activeCount - visibleActiveCount;
        if (overflow != oldOverflow) activeOverflow = overflow > 0 ? "+" + overflow : "";
    }

    static void drawExtras(GuiGraphics graphics, Minecraft client) {
        if (SaveMode.isPaused() || client.level == null || client.player == null) return;
        int height = graphics.guiHeight();
        int width = graphics.guiWidth();
        metric(graphics, client, Widget.COORDINATES, 8, height - 51, Widget.COORDINATES.width, "XYZ", coordinates);
        metric(graphics, client, Widget.CPS, 8, height - 35, METRIC_WIDTH, "CPS", rate);
        if (waypoint != null) metric(graphics, client, Widget.WAYPOINTS, 8, height - 67, METRIC_WIDTH,
                text("Мітка", "Метка", "Mark"), waypoint);
        drawActive(graphics, client, width - METRIC_WIDTH - 8, height / 2);
        drawNotifications(graphics, client);
    }

    private static void metric(GuiGraphics graphics, Minecraft client, Widget widget,
                               int x, int y, int width, String label, String value) {
        if (!beginProjected(graphics, widget, x, y, width, METRIC_HEIGHT, HudSettings.scale())) return;
        try {
            if (width == METRIC_WIDTH) {
                DashHud.drawMetric(graphics, client.font, 0, 0, label, value, HudSettings.accent());
            } else {
                var font = DashTypography.font(client.font);
                DashHud.panel(graphics, 0, 0, width, METRIC_HEIGHT);
                graphics.drawString(font, label, 6, 3, HudSettings.textColor(DashHud.mutedColor()), false);
                String amount = DashHud.fit(font, value, width - 30);
                graphics.drawString(font, amount, width - font.width(amount) - 6, 3,
                        HudSettings.textColor(DashHud.textColor()), false);
            }
        } finally {
            end(graphics);
        }
    }

    private static void drawActive(GuiGraphics graphics, Minecraft client, int x, int y) {
        if (activeCount == 0) return;
        int spacing = 12 + DashAppearance.hudSpacing();
        int overflow = activeCount - visibleActiveCount;
        int height = (visibleActiveCount + (overflow > 0 ? 1 : 0)) * spacing + 3;
        if (!beginProjected(graphics, Widget.ACTIVE_MODULES, x, y, METRIC_WIDTH, height, HudSettings.scale())) return;
        try {
            var font = DashTypography.font(client.font);
            DashHud.panel(graphics, 0, 0, METRIC_WIDTH, height);
            for (int i = 0; i < visibleActiveCount; i++) {
                int top = 3 + i * spacing;
                DashShape.fill(graphics, 5, top + 1, 7, top + 8, 1, HudSettings.color(HudSettings.accent()));
                graphics.drawString(font, DashHud.fit(font, ACTIVE_NAMES[i], METRIC_WIDTH - 16), 10,
                        top, HudSettings.textColor(DashHud.textColor()), false);
            }
            if (overflow > 0) {
                graphics.drawString(font, activeOverflow, 10, 3 + visibleActiveCount * spacing,
                        HudSettings.textColor(DashHud.mutedColor()), false);
            }
        } finally {
            end(graphics);
        }
    }

    private static void drawNotifications(GuiGraphics graphics, Minecraft client) {
        if (noticeCount == 0) return;
        int width = 164;
        int height = noticeCount * 24;
        float defaultScale = Math.min(HudSettings.scale(), graphics.guiWidth() / 408.0F);
        float x = (graphics.guiWidth() - width * defaultScale) * .5F;
        if (!beginProjected(graphics, Widget.NOTIFICATIONS, x, 8, width, height, defaultScale)) return;
        try {
            var font = DashTypography.font(client.font);
            long now = System.nanoTime();
            int background = DashAppearance.panel(0xE9121920);
            for (int i = 0; i < noticeCount; i++) {
                Notice notice = NOTICES[i];
                long age = now - notice.started();
                float fade = Mth.clamp(Math.min(age / 180_000_000F, (NOTICE_DURATION - age) / 240_000_000F), 0, 1);
                int top = i * 24 + Math.round((1 - fade) * 2);
                DashShape.fill(graphics, 0, top, width, top + 22, 5, faded(background, fade));
                DashShape.fill(graphics, 3, top + 5, 5, top + 17, 1, faded(HudSettings.accent(), fade));
                if (fade > .02F) {
                    graphics.drawString(font, DashHud.fit(font, notice.title(), width - 18), 9, top + 2,
                            HudSettings.textColor(withFade(DashHud.textColor(), fade)), false);
                    graphics.drawString(font, DashHud.fit(font, notice.detail(), width - 18), 9, top + 12,
                            HudSettings.textColor(withFade(DashHud.mutedColor(), fade)), false);
                }
            }
        } finally {
            end(graphics);
        }
    }

    private static int faded(int color, float fade) {
        return HudSettings.color(withFade(color, fade));
    }

    private static int withFade(int color, float fade) {
        return (Math.round((color >>> 24) * fade) << 24) | (color & 0xFFFFFF);
    }
}
