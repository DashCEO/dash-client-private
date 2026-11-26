package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

final class AimAssistScreen extends Screen implements DashBindInput.KeyCapture {
    private static final int PANEL_HEIGHT = 228;

    private final Screen parent;
    private final AimAssist aim;
    private float strength;
    private float smoothness;
    private float radius;
    private int duration;
    private AimAssist.Mode mode;
    private int auraTurnSpeed;
    private AimAssist.Priority auraPriority;
    private boolean auraCritTiming;
    private boolean capturingKey;
    private Component status = Component.empty();
    private Button keyButton;
    private Button clearKeyButton;
    private int left;
    private int top;
    private int panelWidth;

    AimAssistScreen(Screen parent, AimAssist aim) {
        super(Component.literal("Aim Assist"));
        this.parent = parent;
        this.aim = aim;
        AimAssist.Settings settings = aim.settings();
        strength = settings.strength();
        smoothness = settings.smoothness();
        radius = settings.radius();
        duration = settings.durationMs();
        mode = settings.mode();
        auraTurnSpeed = settings.auraTurnSpeed();
        auraPriority = settings.auraPriority();
        auraCritTiming = settings.auraCritTiming();
    }

    @Override protected void init() {
        panelWidth = Math.min(340, width - 16);
        left = (width - panelWidth) / 2;
        top = Math.max(2, (height - PANEL_HEIGHT) / 2);
        int x = left + 12;
        int innerWidth = panelWidth - 24;
        int half = (innerWidth - 6) / 2;
        addRenderableWidget(Button.builder(modeLabel(AimAssist.Mode.SMART),
                button -> selectMode(AimAssist.Mode.SMART)).bounds(x, top + 25, half, 20).build());
        addRenderableWidget(Button.builder(modeLabel(AimAssist.Mode.AURA),
                button -> selectMode(AimAssist.Mode.AURA)).bounds(x + half + 6, top + 25, half, 20).build());
        addRenderableWidget(new SettingSlider(x, top + 61, innerWidth, text("Сила", "Сила", "Strength"),
                strength, 0.0F, 1.0F, Units.PERCENT, value -> strength = (float) value));
        addRenderableWidget(new SettingSlider(x, top + 82, innerWidth, text("Плавність", "Плавность", "Smoothness"),
                smoothness, 0.0F, 1.0F, Units.PERCENT, value -> smoothness = (float) value));
        addRenderableWidget(new SettingSlider(x, top + 103, innerWidth, text("Радіус", "Радиус", "Radius"),
                radius, AimAssist.MIN_RADIUS, AimAssist.MAX_RADIUS, Units.BLOCKS, value -> radius = (float) value));
        boolean aura = mode == AimAssist.Mode.AURA;
        if (aura) {
            addRenderableWidget(new SettingSlider(x, top + 124, innerWidth,
                    text("Швидкість повороту", "Скорость поворота", "Turn speed"),
                    auraTurnSpeed, AimAssist.MIN_AURA_TURN_SPEED, AimAssist.MAX_AURA_TURN_SPEED,
                    Units.DEGREES_PER_SECOND, value -> auraTurnSpeed = (int) value));
            addRenderableWidget(Button.builder(priorityLabel(), button -> {
                var priorities = AimAssist.Priority.values();
                auraPriority = priorities[(auraPriority.ordinal() + 1) % priorities.length];
                button.setMessage(priorityLabel());
            }).bounds(x, top + 145, half, 20).build());
            addRenderableWidget(Button.builder(criticalLabel(), button -> {
                auraCritTiming = !auraCritTiming;
                button.setMessage(criticalLabel());
            }).bounds(x + half + 6, top + 145, half, 20).build());
        } else {
            addRenderableWidget(new SettingSlider(x, top + 124, innerWidth,
                    text("Вікно доводки", "Окно доводки", "Correction window"),
                    duration, AimAssist.MIN_DURATION_MS, AimAssist.MAX_DURATION_MS, Units.MILLISECONDS,
                    value -> duration = (int) value));
        }

        int clearWidth = 92;
        int keyTop = top + (aura ? 166 : 150);
        keyButton = addRenderableWidget(Button.builder(keyLabel(), button -> {
            capturingKey = !capturingKey;
            status = Component.empty();
            button.setMessage(keyLabel());
            clearKeyButton.setMessage(clearKeyLabel());
        }).bounds(x, keyTop, innerWidth - clearWidth - 6, 20).build());
        clearKeyButton = addRenderableWidget(Button.builder(clearKeyLabel(), button -> {
            InventoryBindSupport.unbind(minecraft, aim.key());
            status = Component.empty();
            keyButton.setMessage(keyLabel());
        }).bounds(x + innerWidth - clearWidth, keyTop, clearWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal(text("Зберегти", "Сохранить", "Save")), button -> {
            if (aim.update(new AimAssist.Settings(strength, smoothness, radius, duration, mode,
                    auraTurnSpeed, auraPriority, auraCritTiming))) {
                onClose();
            } else {
                status = Component.literal(text("Не вдалося зберегти налаштування.", "Не удалось сохранить настройки.",
                        "Could not save settings."));
            }
        }).bounds(x, keyTop + 22, innerWidth, 20).build());
    }

    private Component modeLabel(AimAssist.Mode candidate) {
        String name = candidate == AimAssist.Mode.SMART ? "Jump Crit Assist" : "Aura";
        return Component.literal(candidate == mode ? "✓ " + name : name);
    }

    private void selectMode(AimAssist.Mode candidate) {
        if (mode == candidate) return;
        mode = candidate;
        clearWidgets();
        init();
    }

    private String modeHint() {
        return mode == AimAssist.Mode.SMART
                ? text("За наявності вікна ЛКМ чекає криту в падінні.",
                        "При наличии окна ЛКМ ждёт крита при падении.",
                        "One click can wait for a natural falling critical.")
                : text("Одиночний світ · огляд і вибір цілей на 360°.",
                        "Одиночный мир · обзор и выбор целей на 360°.",
                        "Local singleplayer · 360° sweep and target selection.");
    }

    private Component priorityLabel() {
        String selection = switch (auraPriority) {
            case NEAREST -> text("Найближча", "Ближайшая", "Nearest");
            case CROSSHAIR -> text("Біля прицілу", "К прицелу", "Crosshair");
            case LOWEST_HEALTH -> text("Найменше HP", "Меньше всего HP", "Lowest HP");
        };
        return Component.literal(text("Ціль · ", "Цель · ", "Target · ") + selection);
    }

    private Component criticalLabel() {
        return Component.literal(auraCritTiming
                ? text("Крити в стрибку · ✓", "Криты в прыжке · ✓", "Jump crits · ✓")
                : text("Крити в стрибку · ×", "Криты в прыжке · ×", "Jump crits · ×"));
    }

    private Component keyLabel() {
        if (capturingKey) return Component.literal(text("Натисни клавішу або кнопку", "Нажми клавишу или кнопку",
                "Press a key or mouse button"));
        return Component.literal(text("Клавіша · ", "Клавиша · ", "Key · ")).append(aim.key().getTranslatedKeyMessage());
    }

    private Component clearKeyLabel() {
        return Component.literal(capturingKey
                ? text("Скасувати", "Отмена", "Cancel")
                : text("Очистити", "Очистить", "Clear key"));
    }

    @Override public boolean captureDashKey(KeyEvent event) {
        if (!capturingKey) return false;
        assign(InputConstants.getKey(event));
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        return captureDashKey(event) || super.keyPressed(event);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!capturingKey) return super.mouseClicked(event, doubleClick);
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && clearKeyButton.isMouseOver(event.x(), event.y())) {
            capturingKey = false;
            status = Component.empty();
            keyButton.setMessage(keyLabel());
            clearKeyButton.setMessage(clearKeyLabel());
        } else {
            assign(InputConstants.Type.MOUSE.getOrCreate(event.button()));
        }
        return true;
    }

    private void assign(InputConstants.Key key) {
        if (InventoryBindSupport.assign(minecraft, aim.key(), key)) {
            capturingKey = false;
            status = Component.empty();
        } else {
            status = Component.literal(text("Клавіша зайнята або недоступна.", "Клавиша занята или недоступна.",
                    "Key is already bound or unavailable."));
        }
        keyButton.setMessage(keyLabel());
        clearKeyButton.setMessage(clearKeyLabel());
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x99080B14);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fillGradient(left, top, left + panelWidth, top + PANEL_HEIGHT,
                HudSettings.shade(.03F), HudSettings.shade(.13F));
        graphics.fill(left, top, left + panelWidth, top + 2, HudSettings.accent());
        graphics.drawString(font, title, left + 12, top + 9, 0xFFF2EEFF, false);
        graphics.drawString(font, font.plainSubstrByWidth(modeHint(), panelWidth - 24), left + 12, top + 50,
                0xFFD6CEE5, false);
        graphics.drawString(font, font.plainSubstrByWidth(status.getString(), panelWidth - 24), left + 12, top + 213,
                0xFFFFD3A5, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }

    private enum Units { PERCENT, BLOCKS, MILLISECONDS, DEGREES_PER_SECOND }

    private static final class SettingSlider extends AbstractSliderButton {
        private final String label;
        private final float min;
        private final float max;
        private final Units units;
        private final DoubleConsumer change;

        SettingSlider(int x, int y, int width, String label, float amount, float min, float max,
                      Units units, DoubleConsumer change) {
            super(x, y, width, 20, Component.empty(), (amount - min) / (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.units = units;
            this.change = change;
            updateMessage();
        }

        private double amount() {
            double raw = min + value * (max - min);
            return switch (units) {
                case PERCENT -> Math.round(raw * 100.0) / 100.0;
                case BLOCKS -> Math.round(raw * 10.0) / 10.0;
                case MILLISECONDS, DEGREES_PER_SECOND -> Math.round(raw);
            };
        }

        @Override protected void updateMessage() {
            String number = switch (units) {
                case PERCENT -> Math.round(amount() * 100.0) + "%";
                case BLOCKS -> String.format(Locale.ROOT, "%.1f %s", amount(), text("бл.", "бл.", "blocks"));
                case MILLISECONDS -> (int) amount() + text(" мс", " мс", " ms");
                case DEGREES_PER_SECOND -> (int) amount() + text("°/с", "°/с", "°/s");
            };
            setMessage(Component.literal(label + " · " + number));
        }

        @Override protected void applyValue() {
            change.accept(amount());
        }
    }
}
