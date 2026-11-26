package com.dash.client.client;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

final class HudSettings {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float MIN_SCALE = 0.65F;
    private static final float MAX_SCALE = 1.40F;
    private static final float DEFAULT_SCALE = 0.75F;
    private static final float MIN_OPACITY = 0.30F;

    private static float scale = DEFAULT_SCALE;
    private static float opacity = 1.0F;
    private static boolean hideEmpty = true;
    private static boolean blur;
    private static boolean cosmicSky = true;
    private static boolean autoChunkReload = true;
    private static boolean hitFeedback = true;
    private static Theme theme = Theme.OBSIDIAN;
    private static DashLanguage language = DashLanguage.UKRAINIAN;
    private static boolean dirty;

    private HudSettings() {}

    static void load() {
        Path file = file();
        if (!Files.isRegularFile(file)) return;

        Properties properties = new Properties();
        try (var input = Files.newBufferedReader(file)) {
            properties.load(input);
            scale = readFloat(properties, "scale", DEFAULT_SCALE, MIN_SCALE, MAX_SCALE);
            opacity = readFloat(properties, "opacity", 1.0F, MIN_OPACITY, 1.0F);
            hideEmpty = Boolean.parseBoolean(properties.getProperty("hideEmpty", "true"));
            blur = Boolean.parseBoolean(properties.getProperty("blur", "false"));
            cosmicSky = Boolean.parseBoolean(properties.getProperty("cosmicSky", "true"));
            autoChunkReload = Boolean.parseBoolean(properties.getProperty("autoChunkReload", "true"));
            hitFeedback = Boolean.parseBoolean(properties.getProperty("hitFeedback", "true"));
            language = DashLanguage.fromCode(properties.getProperty("language"));
            Theme preset = findTheme(properties.getProperty("theme"));
            if (preset != null) theme = preset;
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.warn("Could not read Dash HUD settings", exception);
        }
    }

    static void save() {
        if (!dirty) return;

        Properties properties = new Properties();
        properties.setProperty("scale", Float.toString(scale));
        properties.setProperty("opacity", Float.toString(opacity));
        properties.setProperty("hideEmpty", Boolean.toString(hideEmpty));
        properties.setProperty("blur", Boolean.toString(blur));
        properties.setProperty("cosmicSky", Boolean.toString(cosmicSky));
        properties.setProperty("autoChunkReload", Boolean.toString(autoChunkReload));
        properties.setProperty("hitFeedback", Boolean.toString(hitFeedback));
        properties.setProperty("theme", theme.name());
        properties.setProperty("language", language.code());
        Path file = file();
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "dash-hud-", ".tmp");
            try (var output = Files.newBufferedWriter(temporary)) {
                properties.store(output, "Dash Client HUD");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException exception) {
            LOGGER.warn("Could not save Dash HUD settings", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    LOGGER.debug("Could not remove temporary Dash HUD settings", exception);
                }
            }
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dash-hud.properties");
    }

    private static float readFloat(Properties properties, String key, float fallback, float min, float max) {
        try {
            float value = Float.parseFloat(properties.getProperty(key, Float.toString(fallback)));
            return Float.isFinite(value) ? Mth.clamp(value, min, max) : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    static float scale() { return scale; }
    static float opacity() { return opacity; }
    static boolean hideEmpty() { return hideEmpty; }
    static boolean blur() { return blur; }
    static boolean cosmicSky() { return cosmicSky; }
    static boolean autoChunkReload() { return autoChunkReload; }
    static boolean hitFeedback() { return hitFeedback; }

    static void toggleHitFeedback() {
        hitFeedback = !hitFeedback;
        ManualHitFeedback.clear();
        dirty = true;
    }
    static String themeName() { return theme.label; }
    static String themeId() { return theme.name(); }
    static DashLanguage language() { return language; }

    static void setLanguage(DashLanguage value) {
        if (language == value) return;
        language = value;
        dirty = true;
        save();
    }

    static int presetAccent() { return theme.accent; }
    static int presetBackground() { return theme.base; }
    static int accent() { return DashHudLayout.accentOr(DashAppearance.accent()); }
    static int secondary() {
        int color = DashAppearance.hasCustomAccent()
                ? ARGB.srgbLerp(.54F, DashAppearance.accent(), 0xFFFFFFFF)
                : theme.secondary;
        return DashHudLayout.accentOr(color);
    }
    static boolean isObsidian() { return theme == Theme.OBSIDIAN; }
    static int shade(float tint) {
        return ARGB.srgbLerp(tint, DashAppearance.background(), isObsidian() ? 0xFF858A94 : DashAppearance.accent());
    }

    static int color(int argb) {
        int alpha = Math.round((argb >>> 24) * opacity * DashHudLayout.opacityMultiplier());
        return alpha << 24 | argb & 0xFFFFFF;
    }

    static int textColor(int argb) {
        float textOpacity = Mth.lerp(opacity, .65F, 1.0F) * DashHudLayout.opacityMultiplier();
        int alpha = Math.round((argb >>> 24) * textOpacity);
        return alpha << 24 | argb & 0xFFFFFF;
    }

    static float scaleSlider() { return (scale - MIN_SCALE) / (MAX_SCALE - MIN_SCALE); }
    static float opacitySlider() { return (opacity - MIN_OPACITY) / (1.0F - MIN_OPACITY); }

    static void setScaleSlider(float value) {
        scale = Mth.lerp(value, MIN_SCALE, MAX_SCALE);
        dirty = true;
    }

    static void setOpacitySlider(float value) {
        opacity = Mth.lerp(value, MIN_OPACITY, 1.0F);
        dirty = true;
    }

    static void toggleHideEmpty() {
        hideEmpty = !hideEmpty;
        dirty = true;
    }

    static void toggleAutoChunkReload() {
        autoChunkReload = !autoChunkReload;
        dirty = true;
    }

    static void toggleBlur() {
        blur = !blur;
        dirty = true;
    }

    static void applyAppearance(String presetId, boolean blurred) {
        Theme preset = findTheme(presetId);
        if (preset != null && preset != theme) {
            theme = preset;
            dirty = true;
        }
        if (blur != blurred) {
            blur = blurred;
            dirty = true;
        }
    }

    static void toggleCosmicSky() {
        cosmicSky = !cosmicSky;
        dirty = true;
    }

    static void cycleTheme(int direction) {
        Theme[] presets = Theme.values();
        theme = presets[Math.floorMod(theme.ordinal() + direction, presets.length)];
        dirty = true;
    }

    record Profile(float scale, float opacity, boolean hideEmpty, boolean blur, boolean cosmicSky,
                   boolean autoChunkReload, String theme) {}

    static Profile profile() {
        return new Profile(scale, opacity, hideEmpty, blur, cosmicSky, autoChunkReload, theme.name());
    }

    static boolean validProfile(Profile profile) {
        return profile != null && profile.scale() >= MIN_SCALE && profile.scale() <= MAX_SCALE
                && profile.opacity() >= MIN_OPACITY && profile.opacity() <= 1.0F
                && findTheme(profile.theme()) != null;
    }

    static void applyProfile(Profile profile) {
        if (!validProfile(profile)) throw new IllegalArgumentException("Invalid HUD profile");
        if (profile.equals(profile())) return;

        scale = profile.scale();
        opacity = profile.opacity();
        hideEmpty = profile.hideEmpty();
        blur = profile.blur();
        cosmicSky = profile.cosmicSky();
        autoChunkReload = profile.autoChunkReload();
        theme = Theme.valueOf(profile.theme());
        dirty = true;
    }

    private static Theme findTheme(String name) {
        for (Theme preset : Theme.values()) {
            if (preset.name().equals(name)) return preset;
        }
        return null;
    }

    private enum Theme {
        OBSIDIAN("Obsidian", 0xFF0C0D10, 0xFF61D6FF, 0xFFCFF6FF),
        LAVENDER("Lavender", 0xFF161222, 0xFFB99BFF, 0xFF75E6D4),
        CYBERPUNK("Cyberpunk", 0xFF211327, 0xFFFF4FD7, 0xFFFFE166),
        MIDNIGHT("Midnight", 0xFF111827, 0xFF8FABFC, 0xFFD8E6FF),
        OCEANIC("Oceanic", 0xFF0B202A, 0xFF60D9FF, 0xFF77F1CB),
        EMBER("Ember", 0xFF241811, 0xFFFFA365, 0xFFFFD28B),
        FOREST("Forest", 0xFF12241E, 0xFF84E6AC, 0xFFCEE993),
        ROSE("Rose", 0xFF25151C, 0xFFF28DB2, 0xFFFFCFAD),
        MONO("Mono", 0xFF17191D, 0xFFBCC4D0, 0xFFF0F4FA);

        private final String label;
        private final int base;
        private final int accent;
        private final int secondary;

        Theme(String label, int base, int accent, int secondary) {
            this.label = label;
            this.base = base;
            this.accent = accent;
            this.secondary = secondary;
        }
    }
}
