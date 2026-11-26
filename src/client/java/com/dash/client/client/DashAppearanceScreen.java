package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

final class DashAppearanceScreen extends Screen {
    private static final int TEXT = 0xFFF1F4F8;
    private static final int MUTED = 0xFFA8B2C0;
    private static final int CONTROL = DashAppearance.MENU_CONTROL;
    private static final int BORDER = DashAppearance.MENU_BORDER;
    private static final int GAP = 6;
    private static final Page[] PAGES = Page.values();

    private final Screen parent;
    private Font uiFont;
    private Page page = Page.SURFACE;
    private int panelX, panelY, panelWidth, panelHeight, bodyY, step, controlHeight, half;
    private EditBox accentCode, backgroundCode, themeName;
    private List<String> themes = List.of();
    private int themeIndex;
    private String status = "";
    private boolean invalid;

    DashAppearanceScreen(Screen parent) {
        super(Component.literal(text("Вигляд Dash", "Оформление Dash", "Dash appearance")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        uiFont = DashTypography.font(font);
        panelWidth = Math.max(1, Math.min(width - 12, 320));
        panelHeight = Math.max(1, Math.min(height - 12, 167));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        bodyY = panelY + 54;
        step = Math.min(20, Math.max(17, (panelHeight - 104) / 3));
        controlHeight = step - 3;
        int inner = innerWidth();
        half = (inner - GAP) / 2;
        int tabsWidth = (inner - 2 * GAP) / PAGES.length;
        for (int i = 0; i < PAGES.length; i++) {
            Page tab = PAGES[i];
            button(x() + i * (tabsWidth + GAP), panelY + 29, tabsWidth, 17, tab.label(), tab == page,
                    control -> show(tab));
        }
        switch (page) {
            case SURFACE -> buildSurface();
            case MOTION -> buildMotion();
            case THEMES -> buildThemes();
        }
        button(x(), bottom() - 25, half, 16, text("Скинути вигляд", "Сбросить оформление", "Reset appearance"),
                false, control -> {
                    DashAppearance.reset();
                    status = "";
                    refresh();
                });
        button(rightColumn(), bottom() - 25, half, 16, text("Готово", "Готово", "Done"), true,
                control -> onClose());
    }

    private void buildSurface() {
        int w = Math.max(23, half - 48);
        int actionWidth = half - w - 4;
        accentCode = field(x(), bodyY + 11, w, controlHeight,
                text("Акцент", "Акцент", "Accent color"), DashAppearance.hex(DashAppearance.menuAccentTarget()), 7);
        backgroundCode = field(rightColumn(), bodyY + 11, w, controlHeight,
                text("Колір фону", "Цвет фона", "Background color"), DashAppearance.hex(DashAppearance.menuBackground()), 7);
        String apply = text("Застос.", "Примен.", "Apply");
        button(x() + w + 4, bodyY + 11, actionWidth, controlHeight, apply, true,
                control -> applyColor(true));
        button(rightColumn() + w + 4, bodyY + 11, actionWidth, controlHeight, apply, true,
                control -> applyColor(false));
        slider(DashAppearance.Metric.OPACITY, false, bodyY + step + 12);
        slider(DashAppearance.Metric.RADIUS, true, bodyY + step + 12);
        button(x(), bodyY + 2 * step + 12, half, controlHeight,
                flag(text("Розмиття", "Размытие", "Blur"), DashAppearance.blur()), DashAppearance.blur(),
                control -> {
                    HudSettings.toggleBlur();
                    HudSettings.save();
                    control.setMessage(Component.literal(flag(text("Розмиття", "Размытие", "Blur"),
                            DashAppearance.blur())));
                    ((TileButton) control).selected = DashAppearance.blur();
                });
        int swatchW = (half - 8) / 3;
        int swatchY = bodyY + 2 * step + 12;
        swatch(rightColumn(), swatchY, swatchW, text("Рожевий", "Розовый", "Pink"), DashAppearance.MENU_PINK);
        swatch(rightColumn() + swatchW + 4, swatchY, swatchW,
                text("Лід", "Лёд", "Ice"), DashAppearance.MENU_ICE);
        swatch(rightColumn() + 2 * (swatchW + 4), swatchY, half - 2 * swatchW - 8,
                text("Фіолет", "Фиолет", "Violet"), DashAppearance.MENU_VIOLET);
    }

    private void buildMotion() {
        slider(DashAppearance.Metric.UI_SCALE, false, bodyY);
        slider(DashAppearance.Metric.FONT_SCALE, true, bodyY);
        slider(DashAppearance.Metric.ANIMATION, false, bodyY + step);
        slider(DashAppearance.Metric.SPACING, true, bodyY + step);
    }

    private void buildThemes() {
        themes = DashAppearance.themeNames();
        if (themes.isEmpty()) themeIndex = 0;
        else themeIndex = Mth.clamp(themeIndex, 0, themes.size() - 1);
        boolean present = !themes.isEmpty();
        int arrowWidth = 31;
        int y = bodyY + 1;
        var prev = button(x(), y, arrowWidth, controlHeight, "‹", false, control -> cycle(-1));
        var next = button(x() + innerWidth() - arrowWidth, y, arrowWidth, controlHeight, "›", false,
                control -> cycle(1));
        prev.active = next.active = themes.size() > 1;
        button(x(), bodyY + step + 2, half, controlHeight, text("Завантажити", "Загрузить", "Load"),
                true, control -> loadTheme()).active = present;
        button(rightColumn(), bodyY + step + 2, half, controlHeight, text("Видалити", "Удалить", "Delete"),
                false, control -> deleteTheme()).active = present;
        int saveWidth = Math.min(96, half);
        themeName = field(x(), bodyY + 2 * step + 10, innerWidth() - saveWidth - GAP, controlHeight,
                text("Назва теми", "Название темы", "Theme name"), "", 32);
        button(x() + innerWidth() - saveWidth, bodyY + 2 * step + 10, saveWidth, controlHeight,
                text("Зберегти", "Сохранить", "Save"), true, control -> saveTheme());
    }

    private void slider(DashAppearance.Metric metric, boolean right, int y) {
        addRenderableWidget(new MetricSlider(right ? rightColumn() : x(), y, half, controlHeight, metric));
    }

    private EditBox field(int x, int y, int width, int height, String label, String value, int limit) {
        EditBox field = new EditBox(uiFont, x, y, width, height, Component.literal(label));
        field.setMaxLength(limit);
        field.setValue(value);
        field.setTextColor(TEXT);
        field.setHint(Component.literal(label));
        return addRenderableWidget(field);
    }

    private TileButton button(int x, int y, int width, int height, String label, boolean selected, Button.OnPress press) {
        return addRenderableWidget(new TileButton(x, y, width, height, Component.literal(label), selected, press));
    }

    private void swatch(int x, int y, int width, String label, int color) {
        TileButton swatch = button(x, y, width, controlHeight, label, false, control -> {
            DashAppearance.setAccent(color);
            accentCode.setValue(DashAppearance.hex(color));
            invalid = !DashAppearance.save();
            status = invalid ? saveFailure() : "";
        });
        swatch.swatchColor = color;
    }

    private void applyColor(boolean accent) {
        EditBox field = accent ? accentCode : backgroundCode;
        try {
            int color = DashAppearance.parseHex(field.getValue());
            if (accent) DashAppearance.setAccent(color);
            else DashAppearance.setBackground(color);
            field.setValue(DashAppearance.hex(color));
            invalid = !DashAppearance.save();
            status = invalid ? saveFailure() : text("Колір застосовано", "Цвет применён", "Color applied");
        } catch (IllegalArgumentException ignored) {
            status = text("Введи шість HEX-цифр", "Введи шесть HEX-цифр", "Enter six hex digits");
            invalid = true;
        }
    }

    private void cycle(int change) {
        if (themes.isEmpty()) return;
        themeIndex = Math.floorMod(themeIndex + change, themes.size());
    }

    private void saveTheme() {
        String name = themeName.getValue().strip();
        invalid = !DashAppearance.saveTheme(name);
        status = invalid
                ? text("Тему не збережено. Перевір назву й вільні місця.",
                "Тема не сохранена. Проверь имя и свободные места.", "Theme was not saved. Check its name and available slots.")
                : text("Тему збережено", "Тема сохранена", "Theme saved");
        if (!invalid) {
            List<String> updated = DashAppearance.themeNames();
            for (int i = 0; i < updated.size(); i++) {
                if (name.equalsIgnoreCase(updated.get(i))) {
                    themeIndex = i;
                    break;
                }
            }
            refresh();
        }
    }

    private void loadTheme() {
        if (themes.isEmpty()) return;
        invalid = !DashAppearance.loadTheme(themes.get(themeIndex));
        status = invalid ? saveFailure() : text("Тему застосовано", "Тема применена", "Theme applied");
    }

    private void deleteTheme() {
        if (themes.isEmpty()) return;
        invalid = !DashAppearance.deleteTheme(themes.get(themeIndex));
        status = invalid ? saveFailure() : text("Тему видалено", "Тема удалена", "Theme deleted");
        if (!invalid) refresh();
    }

    private void refresh() {
        clearWidgets();
        init();
    }

    private void show(Page target) {
        if (page == target) return;
        page = target;
        status = "";
        refresh();
    }

    private int x() { return panelX + 10; }
    private int rightColumn() { return x() + half + GAP; }
    private int innerWidth() { return panelWidth - 20; }
    private int bottom() { return panelY + panelHeight; }

    private static String flag(String label, boolean enabled) {
        return label + " · " + (enabled ? text("Увімкнено", "Включено", "On") : text("Вимкнено", "Выключено", "Off"));
    }

    private static String saveFailure() {
        return text("Не вдалося зберегти зміни", "Не удалось сохранить изменения", "Could not save changes");
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (DashAppearance.blur() && minecraft.level != null) renderBlurredBackground(graphics);
        graphics.fill(0, 0, width, height, 0xA308070C);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        DashAppearance.drawPanel(graphics, panelX, panelY, panelX + panelWidth, bottom(),
                DashAppearance.menuBackground(), false);
        int accent = DashAppearance.menuAccent();
        DashShape.fill(graphics, x(), panelY + 12, x() + 4, panelY + 23, 2, accent);
        graphics.drawString(uiFont, title, x() + 10, panelY + 13, TEXT, false);
        String preset = DashHud.fit(uiFont, DashAppearance.menuThemeName(), half);
        graphics.drawString(uiFont, preset, x() + innerWidth() - uiFont.width(preset), panelY + 13, MUTED, false);
        if (page == Page.SURFACE) {
            drawColorCaption(graphics, x(), accentCode.getMessage().getString(), DashAppearance.menuAccentTarget());
            drawColorCaption(graphics, rightColumn(), backgroundCode.getMessage().getString(), DashAppearance.menuBackground());
        } else if (page == Page.THEMES) {
            String selected = themes.isEmpty() ? text("Немає збережених тем", "Нет сохранённых тем", "No saved themes")
                    : themes.get(themeIndex);
            selected = DashHud.fit(uiFont, selected, innerWidth() - 76);
            graphics.drawString(uiFont, selected, x() + (innerWidth() - uiFont.width(selected)) / 2,
                    bodyY + (controlHeight - 8) / 2 + 2, TEXT, false);
        }
        if (!status.isEmpty()) {
            graphics.drawString(uiFont, DashHud.fit(uiFont, status, innerWidth()), x(), bottom() - 37,
                    invalid ? 0xFFFFB9AA : accent, false);
        }
        super.render(graphics, mouseX, mouseY, delta);
    }

    private void drawColorCaption(GuiGraphics graphics, int x, String label, int color) {
        DashShape.frame(graphics, x, bodyY, x + 8, bodyY + 8, 2, BORDER, color);
        graphics.drawString(uiFont, DashHud.fit(uiFont, label, half - 12), x + 12, bodyY, TEXT, false);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void onClose() {
        DashAppearance.save();
        HudSettings.save();
        minecraft.setScreen(parent);
    }

    private enum Page {
        SURFACE, MOTION, THEMES;

        String label() {
            return switch (this) {
                case SURFACE -> text("Панель", "Панель", "Surface");
                case MOTION -> text("Масштаб і рух", "Масштаб и движение", "Scale & motion");
                case THEMES -> text("Теми", "Темы", "Themes");
            };
        }
    }

    private final class TileButton extends Button {
        private boolean selected;
        private int swatchColor;

        TileButton(int x, int y, int width, int height, Component label, boolean selected, OnPress press) {
            super(x, y, width, height, label, press, DEFAULT_NARRATION);
            this.selected = selected;
        }

        @Override
        protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            int accent = DashAppearance.menuAccent();
            boolean hot = active && isHoveredOrFocused();
            boolean chosen = swatchColor == 0 ? selected : swatchColor == DashAppearance.menuAccentTarget();
            int edge = chosen || hot ? (swatchColor == 0 ? accent : swatchColor) : BORDER;
            int fill = hot ? 0xFF2D263A : chosen ? 0xFF242033 : CONTROL;
            int textColor = swatchColor == 0 ? TEXT : swatchColor;
            DashShape.frame(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                    Math.min(DashAppearance.cornerRadius(), 6), edge, fill);
            String shown = DashHud.fit(uiFont, getMessage().getString(), getWidth() - 6);
            graphics.drawString(uiFont, shown, getX() + (getWidth() - uiFont.width(shown)) / 2,
                    getY() + (getHeight() - 8) / 2, active ? textColor : MUTED, false);
        }
    }

    private final class MetricSlider extends AbstractSliderButton {
        private final DashAppearance.Metric metric;
        private final String label;
        private String readout;

        MetricSlider(int x, int y, int width, int height, DashAppearance.Metric metric) {
            super(x, y, width, height, Component.empty(), metric.position());
            this.metric = metric;
            label = metric.label();
            updateMessage();
        }

        @Override protected void updateMessage() {
            readout = metric.display();
            setMessage(Component.literal(label + " · " + readout));
        }

        @Override protected void applyValue() { metric.setPosition((float) value); }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            int x = getX(), y = getY(), right = x + getWidth(), bottom = y + getHeight();
            int accent = DashAppearance.menuAccent();
            DashShape.frame(graphics, x, y, right, bottom, Math.min(DashAppearance.cornerRadius(), 5),
                    isHoveredOrFocused() ? accent : BORDER, CONTROL);
            int rightLabel = right - 6 - uiFont.width(readout);
            graphics.drawString(uiFont, DashHud.fit(uiFont, label, rightLabel - x - 9), x + 6, y + 2, TEXT, false);
            graphics.drawString(uiFont, readout, rightLabel, y + 2, MUTED, false);
            int start = x + 6, end = right - 6;
            int track = bottom - 4;
            int knob = start + (int) Math.round(Math.max(0, end - start - 1) * value);
            DashShape.fill(graphics, start, track, end, track + 2, 1, BORDER);
            if (knob > start) DashShape.fill(graphics, start, track, knob, track + 2, 1, accent);
            DashShape.fill(graphics, knob - 2, track - 2, knob + 3, track + 3, 2, accent);
        }
    }
}
