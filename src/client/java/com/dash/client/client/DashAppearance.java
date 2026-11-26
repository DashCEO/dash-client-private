package com.dash.client.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

/** Local preferences for the Dash surfaces. The game renderer and resource-pack settings have their own options. */
final class DashAppearance {
    static final int MENU_PINK = 0xFFF48BC4;
    static final int MENU_ICE = 0xFF8DDCF5;
    static final int MENU_VIOLET = 0xFFB39AF7;
    static final int MENU_SURFACE = 0xFF15141C;
    static final int MENU_CONTROL = 0xFF1E1C28;
    static final int MENU_BORDER = 0xFF383344;
    private static final int MENU_BASE = 0xFF0D0C12;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern THEME_NAME = Pattern.compile("[\\p{L}\\p{M}\\p{N} _-]{1,32}");
    private static final String PROFILE_KEY = "appearance.dash";
    private static final int MAX_THEMES = 24;
    private static final int MAX_FAVORITES = 128;
    private static final long MAX_FILE_BYTES = 160 * 1024;
    private static final Metric[] METRICS = Metric.values();
    private static final float[] metrics = defaultMetrics();
    private static final Map<String, Theme> themes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private static final Set<String> favorites = new LinkedHashSet<>();
    private static int accentOverride;
    private static int backgroundOverride;
    private static boolean glass = true;
    private static boolean dirty;
    private static int fromAccent;
    private static int toAccent;
    private static long accentChangedAt;

    private DashAppearance() {}

    enum Metric {
        OPACITY("Прозорість панелі", "Прозрачность панели", "Panel opacity", .30F, 1.0F, .94F),
        RADIUS("Радіус кутів", "Радиус углов", "Corner radius", 0.0F, 22.0F, 11.0F),
        UI_SCALE("Масштаб меню", "Масштаб меню", "UI scale", .75F, 1.25F, 1.0F),
        FONT_SCALE("Розмір шрифту", "Размер шрифта", "Font size", .85F, 1.20F, 1.0F),
        ANIMATION("Швидкість анімації", "Скорость анимации", "Animation speed", .45F, 1.8F, 1.0F),
        GLOW("Інтенсивність світла", "Интенсивность света", "Glow intensity", 0.0F, 1.0F, .36F),
        SPACING("Відступи HUD", "Отступы HUD", "HUD spacing", 0.0F, 12.0F, 3.0F),
        REFRACTION("Заломлення краю", "Преломление края", "Edge refraction", 0.0F, 1.0F, .24F),
        CHROMATIC("Кольоровий контур", "Цветовой контур", "Chromatic edge", 0.0F, 1.0F, .12F);

        private final String ukrainian, russian, english;
        private final float min, max, initial;

        Metric(String ukrainian, String russian, String english, float min, float max, float initial) {
            this.ukrainian = ukrainian;
            this.russian = russian;
            this.english = english;
            this.min = min;
            this.max = max;
            this.initial = initial;
        }

        String label() { return DashLanguage.text(ukrainian, russian, english); }

        String display() {
            float value = value();
            return switch (this) {
                case RADIUS, SPACING -> Math.round(value) + " px";
                default -> Math.round(value * 100.0F) + "%";
            };
        }

        float value() { return metrics[ordinal()]; }
        float position() { return (value() - min) / (max - min); }

        void setPosition(float position) {
            float value = Mth.lerp(Mth.clamp(position, 0.0F, 1.0F), min, max);
            if (this == RADIUS || this == SPACING) value = Math.round(value);
            if (Float.isFinite(value) && value != value()) {
                metrics[ordinal()] = value;
                dirty = true;
            }
        }
    }

    static boolean hasCustomAccent() { return accentOverride != 0; }
    static int accentTarget() { return accentOverride == 0 ? HudSettings.presetAccent() : accentOverride; }
    static int background() { return backgroundOverride == 0 ? HudSettings.presetBackground() : backgroundOverride; }
    static String menuThemeName() { return HudSettings.isObsidian() ? "Eclipse" : HudSettings.themeName(); }
    static int menuAccentTarget() {
        return !hasCustomAccent() && HudSettings.isObsidian() ? MENU_PINK : accentTarget();
    }
    static int menuAccent() {
        return !hasCustomAccent() && HudSettings.isObsidian() ? MENU_PINK : accent();
    }
    static int menuBackground() {
        if (backgroundOverride != 0) return backgroundOverride;
        return HudSettings.isObsidian() ? MENU_BASE : ARGB.srgbLerp(.42F, MENU_BASE, HudSettings.presetBackground());
    }
    static float panelOpacity() { return Metric.OPACITY.value(); }
    static int cornerRadius() { return Math.round(Metric.RADIUS.value()); }
    static float uiScale() { return Metric.UI_SCALE.value(); }
    static float fontScale() { return Metric.FONT_SCALE.value(); }
    static float animationScale() { return Metric.ANIMATION.value(); }
    static int hudSpacing() { return Math.round(Metric.SPACING.value()); }
    static boolean blur() { return HudSettings.blur(); }
    static boolean glass() { return glass; }

    static int accent() {
        int requested = accentTarget();
        if (toAccent == 0) {
            fromAccent = requested;
            toAccent = requested;
        }
        if (fromAccent == toAccent && requested == toAccent) return toAccent;
        long now = System.nanoTime();
        int blended = blendAccent(now);
        if (requested != toAccent) {
            fromAccent = blended;
            toAccent = requested;
            accentChangedAt = now;
        }
        return blended;
    }

    private static int blendAccent(long now) {
        float progress = Mth.clamp((now - accentChangedAt) * animationScale() / 200_000_000F, 0.0F, 1.0F);
        if (progress == 1.0F) {
            fromAccent = toAccent;
            return toAccent;
        }
        return ARGB.srgbLerp(progress * (2.0F - progress), fromAccent, toAccent);
    }

    static int panel(int fallback) {
        int surface = backgroundOverride == 0 ? fallback : ARGB.srgbLerp(.72F, fallback | 0xFF000000, backgroundOverride);
        return alpha(surface, Math.round((fallback >>> 24) * panelOpacity()));
    }

    static void drawPanel(GuiGraphics graphics, int x, int y, int right, int bottom, int base, boolean active) {
        int radius = Math.min(cornerRadius(), Math.min(right - x, bottom - y) / 2);
        int edge = active ? ARGB.srgbLerp(.45F, MENU_BORDER, menuAccent()) : MENU_BORDER;
        DashShape.frame(graphics, x, y, right, bottom, radius,
                alpha(edge, Math.round((base >>> 24) * .72F)), panel(base));
    }

    static void setAccent(int argb) {
        accentOverride = argb | 0xFF000000;
        dirty = true;
    }

    static void setBackground(int argb) {
        backgroundOverride = argb | 0xFF000000;
        dirty = true;
    }

    static void setGlass(boolean enabled) {
        glass = enabled;
        dirty = true;
    }

    static void cyclePreset() {
        accentOverride = 0;
        backgroundOverride = 0;
        HudSettings.cycleTheme(1);
        HudSettings.save();
        dirty = true;
        save();
    }

    static void reset() {
        System.arraycopy(defaultMetrics(), 0, metrics, 0, metrics.length);
        accentOverride = 0;
        backgroundOverride = 0;
        glass = true;
        HudSettings.applyAppearance(HudSettings.themeId(), false);
        HudSettings.save();
        dirty = true;
    }

    static boolean isFavorite(String id) { return favorites.contains(id); }

    static boolean toggleFavorite(String id) {
        if (favorites.remove(id)) {
            dirty = true;
            save();
            return false;
        }
        if (!validFavorite(id) || favorites.size() >= MAX_FAVORITES) return false;
        favorites.add(id);
        dirty = true;
        save();
        return true;
    }

    static List<String> themeNames() { return List.copyOf(themes.keySet()); }

    static boolean saveTheme(String candidate) {
        String name = candidate.strip();
        if (!THEME_NAME.matcher(name).matches() || !themes.containsKey(name) && themes.size() >= MAX_THEMES) {
            return false;
        }
        Theme old = themes.put(name, snapshot());
        dirty = true;
        if (save()) return true;
        if (old == null) themes.remove(name);
        else themes.put(name, old);
        return false;
    }

    static boolean loadTheme(String name) {
        Theme theme = themes.get(name);
        if (theme == null) return false;
        apply(theme, true);
        return save();
    }

    static boolean deleteTheme(String name) {
        Theme old = themes.remove(name);
        if (old == null) return false;
        dirty = true;
        if (save()) return true;
        themes.put(name, old);
        return false;
    }

    static String hex(int color) {
        return String.format(Locale.ROOT, "%06X", color & 0xFFFFFF);
    }

    static int parseHex(String color) {
        String value = color.strip();
        if (value.startsWith("#")) value = value.substring(1);
        if (value.length() != 6) throw new IllegalArgumentException("Expected six hexadecimal digits");
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) throw new IllegalArgumentException("Invalid color");
        }
        return Integer.parseInt(value, 16) | 0xFF000000;
    }

    static void load() {
        Path path = file();
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) return;
            try (var input = Files.newBufferedReader(path)) {
                Store stored = GSON.fromJson(input, Store.class);
                if (stored == null) return;
                if (stored.current() != null) apply(stored.current(), false);
                themes.clear();
                if (stored.themes() != null) {
                    for (var entry : stored.themes().entrySet()) {
                        if (themes.size() >= MAX_THEMES) break;
                        if (THEME_NAME.matcher(entry.getKey()).matches() && entry.getValue() != null) {
                            themes.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
                favorites.clear();
                if (stored.favorites() != null) {
                    for (String id : stored.favorites()) {
                        if (favorites.size() >= MAX_FAVORITES) break;
                        if (validFavorite(id)) favorites.add(id);
                    }
                }
                dirty = false;
            }
        } catch (IOException | JsonParseException | IllegalArgumentException problem) {
            LOGGER.warn("Could not read Dash appearance", problem);
        }
    }

    static boolean save() {
        if (!dirty) return true;
        try {
            DashWaypoints.writeJson(file(), GSON.toJson(new Store(snapshot(), themes, favorites)));
            dirty = false;
            return true;
        } catch (IOException problem) {
            LOGGER.warn("Could not save Dash appearance", problem);
            return false;
        }
    }

    static void writeProfile(Properties properties) {
        properties.setProperty(PROFILE_KEY, GSON.toJson(snapshot()));
    }

    static void applyProfile(Properties properties) {
        String stored = properties.getProperty(PROFILE_KEY);
        if (stored == null || stored.length() > 8192) return;
        try {
            Theme selected = GSON.fromJson(stored, Theme.class);
            if (selected != null) {
                apply(selected, true);
                save();
            }
        } catch (JsonParseException | IllegalArgumentException problem) {
            LOGGER.warn("Ignoring invalid Dash appearance in profile", problem);
        }
    }

    private static boolean validFavorite(String id) { return id != null && !id.isBlank() && id.length() <= 160; }
    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("dash-appearance.json"); }
    private static int alpha(int rgb, int alpha) { return (alpha << 24) | (rgb & 0xFFFFFF); }

    private static float[] defaultMetrics() {
        float[] values = new float[METRICS.length];
        for (Metric metric : METRICS) values[metric.ordinal()] = metric.initial;
        return values;
    }

    private static Theme snapshot() {
        return new Theme(HudSettings.themeId(), blur(), glass, accentOverride, backgroundOverride, metrics.clone());
    }

    private static void apply(Theme theme, boolean updateLegacy) {
        for (Metric metric : METRICS) {
            int index = metric.ordinal();
            float[] stored = theme.metrics();
            float value = stored != null && index < stored.length ? stored[index] : metric.initial;
            metrics[index] = Float.isFinite(value) ? Mth.clamp(value, metric.min, metric.max) : metric.initial;
        }
        accentOverride = theme.accent() == 0 ? 0 : theme.accent() | 0xFF000000;
        backgroundOverride = theme.background() == 0 ? 0 : theme.background() | 0xFF000000;
        glass = theme.glass();
        if (updateLegacy) {
            HudSettings.applyAppearance(theme.preset(), theme.blur());
            HudSettings.save();
        }
        dirty = true;
    }

    private record Theme(String preset, boolean blur, boolean glass, int accent, int background, float[] metrics) {}
    private record Store(Theme current, Map<String, Theme> themes, Set<String> favorites) {}
}
