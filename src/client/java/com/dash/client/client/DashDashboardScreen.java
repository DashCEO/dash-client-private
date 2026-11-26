package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

public final class DashDashboardScreen extends Screen {
    private static final int TEXT = 0xFFF3F5F8;
    private static final int MUTED = 0xFF9DA7B5;
    private static final int EDGE = 0xFF34313E;
    private static final int ROW = 0xFF1C1A24;
    private static final int GAP = 7;
    private static final int ROW_HEIGHT = 23;
    private static final int HUD_ROW_HEIGHT = 21;
    private static final int MAX_VISIBLE_ROWS = 8;
    private static final float PANEL_SCALE = .75F;
    private static final DashHudLayout.Widget[] ELEMENTS = DashHudLayout.Widget.values();
    private static final TriggerBot.TargetType[] TARGETS = TriggerBot.TargetType.values();
    private static final DashLanguage[] LANGUAGES = DashLanguage.values();
    private static final Nav[] NAV = Nav.values();
    private static final int PAGE_COUNT = 3;
    private static final int MODULES = 0, OPTIONS = 1, HUD = 2;

    private final Screen parent;
    private final long opened = System.nanoTime();
    private final List<ModuleRow> modules = new ArrayList<>();
    private final List<ModuleRow> results = new ArrayList<>();
    private final Map<String, KeyMapping> bindings = new HashMap<>();
    private final Hit[] hits = new Hit[160];
    private final float[] elementToggles = new float[ELEMENTS.length];
    private EditBox search;
    private Font uiFont;
    private Nav category = Nav.ALL;
    private ModuleRow selected;
    private boolean favoritesOnly;
    private int compactSection;
    private int moduleOffset, hudOffset;
    private int hitCount;
    private int lastMouseX, lastMouseY;
    private int panelWidth, panelHeight, railWidth, contentX, contentWidth, bodyY, bodyHeight;
    private int moduleListY, moduleListHeight, moduleRowStep, elementsListY, elementsListHeight, hudRowStep;
    private float scale, panelLeft, panelTop;
    private float progress, hoverStep, fontScale;
    private int accent;
    private long frameTime, closeAt, changedAt;
    private String toolbarHint;
    private int toolbarHintCenter;
    private Action dragging;
    private KeyMapping captureKey;
    private KeyMapping cachedKey;
    private String cachedKeyLabel;
    private int dragLeft, dragRight;
    private Rect searchBounds = new Rect(0, 0, 0, 0);

    public DashDashboardScreen() {
        this(null);
    }

    DashDashboardScreen(Screen parent) {
        super(Component.literal("Dash Client"));
        this.parent = parent;
        for (int i = 0; i < hits.length; i++) hits[i] = new Hit();
    }

    @Override
    protected void init() {
        uiFont = DashTypography.font(font);
        layout();
        String previous = search == null ? "" : search.getValue();
        search = addWidget(new EditBox(uiFont, 0, 0, Math.max(20, searchBounds.width() - 19), 13,
                searchLabel()));
        search.setBordered(false);
        search.setMaxLength(64);
        search.setTextColor(TEXT);
        search.setHint(searchLabel());
        placeSearch();
        search.setValue(previous);
        search.setResponder(value -> {
            compactSection = MODULES;
            filter();
        });
        bindings.clear();
        for (var binding : minecraft.options.keyMappings) bindings.put(binding.getName(), binding);
        if (modules.isEmpty()) {
            for (var module : DashClientClient.dashboardModules()) modules.add(new ModuleRow(module));
            for (int i = 0; i < ELEMENTS.length; i++) elementToggles[i] = DashHudLayout.enabled(ELEMENTS[i]) ? 1 : 0;
        }
        filter();
    }

    private void filter() {
        hitCount = 0;
        results.clear();
        String query = search.getValue().trim().toLowerCase(Locale.ROOT);
        for (var row : modules) {
            var module = row.module;
            boolean categoryMatch = category == Nav.ALL || !query.isEmpty() || category.key.equals(module.category());
            if (categoryMatch && (!favoritesOnly || DashAppearance.isFavorite(module.id()))
                    && (query.isEmpty() || module.id().toLowerCase(Locale.ROOT).contains(query)
                    || module.label().get().toLowerCase(Locale.ROOT).contains(query))) {
                results.add(row);
            }
        }
        moduleOffset = 0;
        if (!results.contains(selected)) selected = results.isEmpty() ? null : results.getFirst();
        changedAt = System.nanoTime();
    }

    private void layout() {
        float wanted = DashAppearance.uiScale();
        // Keep the previous responsive bounds as the reference, then scale both physical dimensions.
        boolean narrowViewport = width / wanted < 537 || height / wanted < 269;
        panelWidth = Mth.clamp((int) (width / wanted) - 24, narrowViewport ? 344 : 530,
                narrowViewport ? 490 : 900);
        panelHeight = Mth.clamp((int) (height / wanted) - 22, narrowViewport ? 310 : 284, 576);
        scale = PANEL_SCALE * Math.min(wanted, Math.min(Math.max(1, width - 8) / (float) panelWidth,
                Math.max(1, height - 8) / (float) panelHeight));
        int density = minecraft.getWindow().getGuiScale();
        panelLeft = DashTypography.snap((width - panelWidth * scale) * 0.5F, density);
        panelTop = DashTypography.snap((height - panelHeight * scale) * 0.5F, density);
        railWidth = narrowViewport ? 92 : 103;
        contentX = 11 + railWidth + GAP;
        contentWidth = panelWidth - contentX - 11;
        int titleWidth = narrowViewport ? 62 : Mth.clamp(contentWidth / 4, 80, 125);
        int searchX = contentX + titleWidth;
        int searchW = Math.max(24, panelWidth - 100 - searchX);
        if (searchBounds.x() != searchX || searchBounds.width() != searchW) {
            searchBounds = new Rect(searchX, 15, searchW, 22);
            if (search != null) placeSearch();
        }
        bodyY = 77;
        bodyHeight = panelHeight - bodyY - 12;
    }

    private void placeSearch() {
        search.setX(searchBounds.x() + 15);
        search.setY(searchBounds.y() + 6);
        search.setWidth(Math.max(8, searchBounds.width() - 20));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        layout();
        long now = System.nanoTime();
        long duration = (long) (200_000_000L / Math.max(0.15F, DashAppearance.animationScale()));
        float openedFraction = Mth.clamp((now - opened) / (float) duration, 0, 1);
        float closedFraction = closeAt == 0 ? 0 : Mth.clamp((now - closeAt) / (float) duration, 0, 1);
        progress = ease(openedFraction) * (1 - ease(closedFraction));
        if (closedFraction >= 1) {
            minecraft.setScreen(parent);
            return;
        }
        hoverStep = frameTime == 0 ? 1 : Mth.clamp((now - frameTime) / (duration * .45F), 0.016F, 1);
        frameTime = now;
        fontScale = DashAppearance.fontScale();
        accent = DashAppearance.menuAccent();
        hitCount = 0;
        toolbarHint = null;
        lastMouseX = (int) Math.floor((mouseX - panelLeft) / scale);
        lastMouseY = (int) Math.floor((mouseY - panelTop) / scale);
        graphics.fill(0, 0, width, height, alpha(0xE0060609));
        var pose = graphics.pose();
        pose.pushMatrix();
        try {
            pose.translate(panelLeft, panelTop).scale(scale);
            DashShape.frame(graphics, 0, 0, panelWidth, panelHeight, radius(0), alpha(EDGE),
                    alpha(DashAppearance.panel(DashAppearance.menuBackground())));
            rail(graphics);
            toolbar(graphics, partialTick);
            tabs(graphics);
            switch (compactSection) {
                case OPTIONS -> options(graphics);
                case HUD -> elements(graphics);
                default -> function(graphics);
            }
            drawToolbarHint(graphics);
        } finally {
            pose.popMatrix();
        }
    }

    private void rail(GuiGraphics graphics) {
        int x = 11;
        int right = x + railWidth;
        frame(graphics, x, 11, right, panelHeight - 12, 8, 0xFF100F16);
        icon(graphics, Icon.DASH, x + 11, 23, accent);
        label(graphics, "DASH", x + 28, 25, TEXT, right - x - 34);

        int rowY = 52;
        int step = Math.min(23, (panelHeight - 90) / NAV.length);
        for (int i = 0; i < NAV.length; i++) {
            Nav nav = NAV[i];
            if (i == Nav.CONFIGS.ordinal()) {
                graphics.fill(x + 10, rowY, right - 10, rowY + 1, alpha(EDGE));
                rowY += 6;
            }
            boolean active = category == nav;
            boolean hovered = inside(lastMouseX, lastMouseY, x + 5, rowY, right - 5, rowY + step - 1);
            if (active || hovered) fill(graphics, x + 5, rowY, right - 5, rowY + step - 1,
                    active ? 0xFF292237 : 0xFF201C2B, 5);
            if (active) fill(graphics, x + 5, rowY + 2, x + 7, rowY + step - 3, accent, 1);
            icon(graphics, nav.icon, x + 11, rowY + (step - 11) / 2, active ? accent : MUTED);
            label(graphics, nav.label(), x + 27, rowY + (step - 9) / 2, active ? TEXT : MUTED, right - x - 31);
            hit(Action.NAVIGATE, i, x + 5, rowY, right - 5, rowY + step - 1);
            rowY += step;
        }
        int footer = panelHeight - 33;
        button(graphics, x + 7, footer, right - 7, footer + 15,
                text("Призупинити", "Приостановить", "Pause"), Icon.PAUSE, Action.PAUSE, 0);
    }

    private void toolbar(GuiGraphics graphics, float tick) {
        frame(graphics, contentX, 11, panelWidth - 11, 41, radius(-1), 0xFF15131B);
        label(graphics, category.label(), contentX + 8, 21, TEXT, searchBounds.x() - contentX - 12);
        boolean focused = getFocused() == search;
        DashShape.frame(graphics, searchBounds.x(), searchBounds.y(), searchBounds.right(),
                searchBounds.y() + searchBounds.height(), 6, alpha(focused ? DashAppearance.MENU_ICE : EDGE),
                alpha(0xFF100F16));
        icon(graphics, Icon.SEARCH, searchBounds.x() + 4, searchBounds.y() + 6, MUTED);
        hit(Action.FOCUS_SEARCH, 0, searchBounds.x(), searchBounds.y(), searchBounds.right(), searchBounds.y() + searchBounds.height());
        if (progress > .45F) search.render(graphics, lastMouseX, lastMouseY, tick);
        int start = panelWidth - 96;
        toolbarButton(graphics, start, Icon.STAR, Action.FAVORITES, favoritesOnly,
                text("Обране", "Избранное", "Favorites"));
        toolbarButton(graphics, start + 21, Icon.LANGUAGE, Action.LANGUAGE, false,
                HudSettings.language().displayName());
        toolbarButton(graphics, start + 42, Icon.PALETTE, Action.THEME, false, DashAppearance.menuThemeName());
        toolbarButton(graphics, start + 63, Icon.FULL, Action.FULLSCREEN, minecraft.getWindow().isFullscreen(),
                text("На весь екран", "Полный экран", "Fullscreen"));
    }

    private void toolbarButton(GuiGraphics graphics, int x, Icon icon, Action action, boolean active, String hint) {
        boolean hovered = inside(lastMouseX, lastMouseY, x, 16, x + 18, 36);
        fill(graphics, x, 16, x + 18, 36, active ? 0xFF35263D : hovered ? 0xFF2A2534 : ROW, 5);
        icon(graphics, icon, x + 4, 21, active ? accent : TEXT);
        hit(action, 0, x, 16, x + 18, 36);
        if (hovered) {
            toolbarHint = hint;
            toolbarHintCenter = x + 9;
        }
    }

    private void drawToolbarHint(GuiGraphics graphics) {
        if (toolbarHint == null) return;
        int hintWidth = width(toolbarHint) + 10;
        int hintX = Mth.clamp(toolbarHintCenter - hintWidth / 2, contentX, panelWidth - hintWidth - 12);
        fill(graphics, hintX, 42, hintX + hintWidth, 56, 0xFF252130, 4);
        label(graphics, toolbarHint, hintX + 5, 44, TEXT, hintWidth - 9);
    }

    private void tabs(GuiGraphics graphics) {
        int segment = (contentWidth - 2 * GAP) / PAGE_COUNT;
        for (int i = 0; i < PAGE_COUNT; i++) {
            int x = contentX + (segment + GAP) * i;
            int right = i == PAGE_COUNT - 1 ? contentX + contentWidth : x + segment;
            boolean active = i == compactSection;
            boolean hovered = inside(lastMouseX, lastMouseY, x, 49, right, 69);
            fill(graphics, x, 49, right, 69, active ? 0xFF2C2339 : hovered ? 0xFF252130 : ROW, 5);
            if (active) fill(graphics, x + 6, 67, right - 6, 69, DashAppearance.MENU_VIOLET, 1);
            label(graphics, sectionLabel(i), x + 6, 55, active ? TEXT : MUTED, right - x - 10);
            hit(Action.COMPACT_PAGE, i, x, 49, right, 69);
        }
    }

    private void function(GuiGraphics graphics) {
        int right = contentX + contentWidth;
        section(graphics, contentX, right, sectionLabel(MODULES), Icon.GRID, Integer.toString(results.size()));
        moduleListY = bodyY + 29;
        int bottom = bodyY + bodyHeight - 6;
        moduleListHeight = bottom - moduleListY;
        moduleRowStep = rowStep(moduleListHeight, ROW_HEIGHT);
        int visible = Math.max(1, moduleListHeight / moduleRowStep);
        moduleOffset = Mth.clamp(moduleOffset, 0, Math.max(0, results.size() - visible));
        graphics.enableScissor(contentX + 3, moduleListY, right - 3, moduleListY + moduleListHeight);
        try {
            if (results.isEmpty()) {
                label(graphics, text("Нічого не знайдено", "Ничего не найдено", "No matching modules"),
                        contentX + 8, moduleListY + 5, MUTED, contentWidth - 16);
            }
            int limit = Math.min(results.size(), moduleOffset + visible + 1);
            for (int i = moduleOffset; i < limit; i++) {
                ModuleRow row = results.get(i);
                int y = moduleListY + (i - moduleOffset) * moduleRowStep;
                moduleRow(graphics, row, i, contentX + 6, y, right - 6);
            }
        } finally {
            graphics.disableScissor();
        }
        scrollbar(graphics, right - 4, moduleListY, moduleListHeight, results.size(), visible, moduleOffset);
    }

    private void options(GuiGraphics graphics) {
        int right = contentX + contentWidth;
        section(graphics, contentX, right, sectionLabel(OPTIONS), Icon.SLIDERS, null);
        int x = contentX + 8;
        int y = bodyY + 29;
        if (selected == null) {
            label(graphics, text("Обери модуль зі списку", "Выбери модуль из списка", "Select a module from the list"),
                    x, y + 4, MUTED, contentWidth - 16);
            return;
        }
        inspector(graphics, selected.module, x, y, right - 8, y + inspectorHeight(selected.module));
    }

    private void moduleRow(GuiGraphics graphics, ModuleRow row, int index, int x, int y, int right) {
        int bottom = y + moduleRowStep - 3;
        int clipped = Math.min(bottom, moduleListY + moduleListHeight);
        int center = (y + bottom) / 2;
        boolean hovering = inside(lastMouseX, lastMouseY, x, y, right, clipped);
        row.hover = Mth.lerp(hoverStep, row.hover, hovering ? 1 : 0);
        boolean on = row.module.enabled().getAsBoolean();
        row.toggle = Mth.lerp(hoverStep, row.toggle, on ? 1 : 0);
        int base = ARGB.srgbLerp(row.hover, row == selected ? 0xFF262030 : ROW, 0xFF2C2639);
        fill(graphics, x, y, right, bottom, base, 5);
        if (on) fill(graphics, x + 1, center - 6, x + 3, center + 6, accent, 1);
        int switchX = right - 26;
        int starX = switchX - 16;
        int settingsX = starX - 16;
        label(graphics, row.module.label().get(), x + 7, center - 4, TEXT, settingsX - x - 8);
        icon(graphics, Icon.SLIDERS, settingsX + 1, center - 5, hovering ? DashAppearance.MENU_ICE : MUTED);
        icon(graphics, Icon.STAR, starX + 1, center - 5, DashAppearance.isFavorite(row.module.id()) ? accent : 0xFF737E8E);
        drawSwitch(graphics, switchX, center - 5, row.toggle);
        hit(Action.SELECT_MODULE, index, x, y, starX, clipped);
        hit(Action.STAR_MODULE, index, starX, y, switchX, clipped);
        hit(Action.TOGGLE_MODULE, index, switchX, y, right, clipped);
    }

    private int inspectorHeight(DashClientClient.DashboardModule module) {
        int height = switch (module.id()) {
            case "Trigger Bot" -> 79;
            case "Velocity" -> 66;
            default -> module.configure() == null ? 48 : 62;
        };
        return bindingFor(module) == null ? height : height + 20;
    }

    private KeyMapping bindingFor(DashClientClient.DashboardModule module) {
        return switch (module.id()) {
            case "Trigger Bot" -> bindings.get("key.dash-client.trigger_bot");
            case "Aim Assist" -> bindings.get("key.dash-client.aim-assist");
            case "Freelook" -> bindings.get("key.dash-client.freelook");
            case "Marks" -> bindings.get("key.dash-client.marks");
            case "Zoom" -> bindings.get("key.dash-client.zoom");
            default -> null;
        };
    }

    private void inspector(GuiGraphics graphics, DashClientClient.DashboardModule module, int x, int y, int right, int bottom) {
        frame(graphics, x, y, right, bottom, 6, 0xFF18151F);
        label(graphics, module.label().get(), x + 6, y + 7, TEXT, right - x - 12);
        if (module.id().equals("Trigger Bot")) {
            var range = (float) ((TriggerBot.range() - TriggerBot.MIN_RANGE) / (TriggerBot.MAX_RANGE - TriggerBot.MIN_RANGE));
            String blocks = Math.round(TriggerBot.range() * 10) / 10F + text(" бл.", " бл.", " blk.");
            slider(graphics, x + 6, y + 23, right - 6, range, blocks,
                    text("Дальність", "Дальность", "Range"), Action.TRIGGER_RANGE);
            int half = (right - x - 16) / 2;
            for (int i = 0; i < TARGETS.length; i++) {
                int left = x + 6 + (i % 2) * (half + 4);
                int top = y + 43 + (i / 2) * 16;
                boolean on = TriggerBot.targets(TARGETS[i]);
                fill(graphics, left, top, left + half, top + 14, on ? 0xFF282438 : ROW, 4);
                label(graphics, targetLabel(TARGETS[i]), left + 4, top + 3,
                        on ? DashAppearance.MENU_ICE : MUTED, half - 6);
                hit(Action.TRIGGER_TARGET, i, left, top, left + half, top + 14);
            }
        } else if (module.id().equals("Velocity")) {
            slider(graphics, x + 6, y + 28, right - 6, Velocity.force(), percentage(Velocity.force()),
                    text("Відкидання", "Отбрасывание", "Knockback"), Action.VELOCITY);
        } else if (module.configure() != null) {
            label(graphics, module.category(), x + 6, y + 22, MUTED, right - x - 12);
            button(graphics, x + 6, y + 36, right - 6, y + 55,
                    text("Налаштувати", "Настроить", "Configure"), Icon.SLIDERS, Action.CONFIGURE, 0);
        } else {
            label(graphics, module.enabled().getAsBoolean() ? text("Увімкнено", "Включено", "Enabled")
                    : text("Вимкнено", "Выключено", "Disabled"), x + 6, y + 25,
                    module.enabled().getAsBoolean() ? accent : MUTED, right - x - 12);
        }
        KeyMapping binding = bindingFor(module);
        if (binding != null) {
            if (cachedKey != binding) {
                cachedKey = binding;
                cachedKeyLabel = binding.getTranslatedKeyMessage().getString();
            }
            int top = bottom - 21;
            button(graphics, x + 6, top, right - 6, bottom - 5,
                    captureKey == binding ? text("Натисни клавішу", "Нажми клавишу", "Press a key")
                            : text("Клавіша ", "Клавиша ", "Key ") + cachedKeyLabel,
                    null, Action.KEY_CAPTURE, 0);
        }
    }

    private static String targetLabel(TriggerBot.TargetType type) {
        return switch (type) {
            case PLAYERS -> text("Гравці", "Игроки", "Players");
            case HOSTILE -> text("Ворожі", "Враждебные", "Hostile");
            case NEUTRAL -> text("Нейтральні", "Нейтральные", "Neutral");
            case PASSIVE -> text("Мирні", "Мирные", "Passive");
        };
    }

    private void elements(GuiGraphics graphics) {
        int right = contentX + contentWidth;
        int enabled = 0;
        for (var element : ELEMENTS) if (DashHudLayout.enabled(element)) enabled++;
        section(graphics, contentX, right, sectionLabel(HUD), Icon.ELEMENTS, enabled + "/" + ELEMENTS.length);
        elementsListY = bodyY + 29;
        int bottom = bodyY + bodyHeight - 7;
        elementsListHeight = bottom - 49 - elementsListY;
        hudRowStep = rowStep(elementsListHeight, HUD_ROW_HEIGHT);
        int visible = Math.max(1, elementsListHeight / hudRowStep);
        hudOffset = Mth.clamp(hudOffset, 0, Math.max(0, ELEMENTS.length - visible));
        graphics.enableScissor(contentX + 3, elementsListY, right - 3, elementsListY + elementsListHeight);
        try {
            int limit = Math.min(ELEMENTS.length, hudOffset + visible + 1);
            for (int i = hudOffset; i < limit; i++) {
                int x = contentX + 6;
                int end = right - 6;
                int y = elementsListY + (i - hudOffset) * hudRowStep;
                int rowBottom = y + hudRowStep - 3;
                int clipped = Math.min(rowBottom, elementsListY + elementsListHeight);
                int center = (y + rowBottom) / 2;
                boolean enabledElement = DashHudLayout.enabled(ELEMENTS[i]);
                boolean hover = inside(lastMouseX, lastMouseY, x, y, end, clipped);
                fill(graphics, x, y, end, rowBottom, hover ? 0xFF2A2335 : ROW, 4);
                fill(graphics, x + 4, center - 2, x + 7, center + 2, enabledElement ? DashAppearance.MENU_ICE : MUTED, 1);
                label(graphics, ELEMENTS[i].label(), x + 10, center - 4, TEXT, end - x - 47);
                icon(graphics, Icon.SLIDERS, end - 39, center - 5, hover ? DashAppearance.MENU_ICE : MUTED);
                elementToggles[i] = Mth.lerp(hoverStep, elementToggles[i], enabledElement ? 1 : 0);
                drawSwitch(graphics, end - 25, center - 5, elementToggles[i]);
                hit(Action.EDIT_ELEMENT, i, x, y, end - 26, clipped);
                hit(Action.TOGGLE_ELEMENT, i, end - 26, y, end, clipped);
            }
        } finally {
            graphics.disableScissor();
        }
        scrollbar(graphics, right - 4, elementsListY, elementsListHeight, ELEMENTS.length, visible, hudOffset);
        int x = contentX + 8;
        int end = right - 8;
        int middle = (x + end) / 2;
        slider(graphics, x, bottom - 43, middle - GAP, HudSettings.opacitySlider(),
                percentage(HudSettings.opacity()), text("Прозорість", "Прозрачность", "Opacity"), Action.HUD_OPACITY);
        slider(graphics, middle + GAP, bottom - 43, end, HudSettings.scaleSlider(),
                percentage(HudSettings.scale()), text("Масштаб", "Масштаб", "Scale"), Action.HUD_SCALE);
        button(graphics, contentX + 6, bottom - 19, right - 6, bottom,
                text("Розташувати HUD", "Разместить HUD", "Arrange HUD"), Icon.FULL, Action.HUD_EDITOR, 0);
    }

    private static int rowStep(int listHeight, int minimum) {
        return Math.max(minimum, (listHeight + MAX_VISIBLE_ROWS - 1) / MAX_VISIBLE_ROWS);
    }

    private void section(GuiGraphics graphics, int x, int right, String title, Icon icon, String badge) {
        frame(graphics, x, bodyY, right, bodyY + bodyHeight, radius(-1), 0xFF121119);
        fill(graphics, x + 4, bodyY + 4, right - 4, bodyY + 23, 0xFF1C1925, 5);
        icon(graphics, icon, x + 8, bodyY + 8, DashAppearance.MENU_ICE);
        int reserved = badge == null ? 14 : width(badge) + 18;
        label(graphics, title, x + 25, bodyY + 9, TEXT, right - x - 25 - reserved);
        if (badge != null) label(graphics, badge, right - width(badge) - 10, bodyY + 9, MUTED, reserved - 8);
        long elapsed = frameTime - changedAt;
        float line = 1 - Mth.clamp(elapsed / 230_000_000F, 0, 1);
        if (line > 0) fill(graphics, x + 6, bodyY + 23, x + 6 + Math.round((right - x - 12) * line), bodyY + 24, accent, 1);
    }

    private static String sectionLabel(int section) {
        return switch (section) {
            case MODULES -> text("Модулі", "Модули", "Modules");
            case OPTIONS -> text("Опції", "Опции", "Options");
            default -> text("Віджети HUD", "Виджеты HUD", "HUD widgets");
        };
    }

    private void slider(GuiGraphics graphics, int x, int y, int right, float amount, String value,
                        String name, Action action) {
        int handle = x + Math.round((right - x) * amount);
        int valueWidth = width(value);
        label(graphics, name, x, y, MUTED, right - x - valueWidth - 4);
        label(graphics, value, right - valueWidth, y, TEXT, valueWidth + 2);
        fill(graphics, x, y + 13, right, y + 16, EDGE, 1);
        fill(graphics, x, y + 13, handle, y + 16, DashAppearance.MENU_ICE, 1);
        fill(graphics, handle - 2, y + 11, handle + 3, y + 18, TEXT, 2);
        hit(action, 0, x, y + 8, right + 1, y + 20);
    }

    private static String percentage(float amount) {
        return Math.round(amount * 100) + "%";
    }

    private void drawSwitch(GuiGraphics graphics, int x, int y, float amount) {
        int color = ARGB.srgbLerp(amount, EDGE, accent);
        fill(graphics, x, y, x + 22, y + 10, color, 5);
        int knob = x + 2 + Math.round(12 * amount);
        fill(graphics, knob, y + 2, knob + 6, y + 8, TEXT, 3);
    }

    private void button(GuiGraphics graphics, int x, int y, int right, int bottom,
                        String label, Icon icon, Action action, int data) {
        boolean hovered = inside(lastMouseX, lastMouseY, x, y, right, bottom);
        fill(graphics, x, y, right, bottom, hovered ? 0xFF2A2335 : ROW, 5);
        if (icon != null) icon(graphics, icon, x + 5, y + (bottom - y - 11) / 2, hovered ? accent : MUTED);
        int textX = x + (icon == null ? 5 : 19);
        label(graphics, label, textX, y + (bottom - y - 9) / 2, TEXT, right - textX - 4);
        hit(action, data, x, y, right, bottom);
    }

    private void scrollbar(GuiGraphics graphics, int x, int y, int height, int count, int visible, int offset) {
        if (height <= 0 || count <= visible) return;
        int handle = Math.max(9, height * visible / count);
        int offsetY = (height - handle) * offset / (count - visible);
        fill(graphics, x, y + offsetY, x + 2, y + offsetY + handle, DashAppearance.MENU_VIOLET, 1);
    }

    private void frame(GuiGraphics graphics, int x, int y, int right, int end, int radius, int color) {
        DashShape.frame(graphics, x, y, right, end, radius, alpha(EDGE), alpha(DashAppearance.panel(color)));
    }

    private void fill(GuiGraphics graphics, int x, int y, int right, int end, int color, int radius) {
        DashShape.fill(graphics, x, y, right, end, radius, alpha(color));
    }

    private int alpha(int argb) {
        return Math.round((argb >>> 24) * progress) << 24 | argb & 0xFFFFFF;
    }

    private static float ease(float value) {
        float remaining = 1 - value;
        return 1 - remaining * remaining * remaining;
    }

    private int radius(int offset) {
        return Math.max(2, DashAppearance.cornerRadius() + offset);
    }

    private int width(String text) {
        return Math.round(uiFont.width(text) * fontScale);
    }

    private void label(GuiGraphics graphics, String value, int x, int y, int color, int maxWidth) {
        if (maxWidth <= 0) return;
        String fitted = DashHud.fit(uiFont, value, (int) (maxWidth / fontScale));
        if (Math.abs(fontScale - 1) < .01F) {
            graphics.drawString(uiFont, fitted, x, y, alpha(color), false);
        } else {
            var pose = graphics.pose();
            pose.pushMatrix();
            try {
                pose.translate(x, y).scale(fontScale);
                graphics.drawString(uiFont, fitted, 0, 0, alpha(color), false);
            } finally {
                pose.popMatrix();
            }
        }
    }

    private void icon(GuiGraphics graphics, Icon icon, int x, int y, int color) {
        DashDashboardIcons.draw(graphics, icon, x, y, alpha(color));
    }

    private void hit(Action action, int data, int x, int y, int right, int bottom) {
        if (x >= right || y >= bottom || hitCount >= hits.length) return;
        Hit hit = hits[hitCount++];
        hit.action = action;
        hit.data = data;
        hit.x = x;
        hit.y = y;
        hit.right = right;
        hit.bottom = bottom;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (closeAt != 0) return true;
        captureKey = null;
        double x = localX(event.x()), y = localY(event.y());
        if (search.mouseClicked(new MouseButtonEvent(x, y, event.buttonInfo()), doubleClick)) {
            if (compactSection != MODULES) showSection(MODULES);
            setFocused(search);
            return true;
        }
        setFocused(null);
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            for (int i = hitCount - 1; i >= 0; i--) {
                Hit hit = hits[i];
                if ((hit.action == Action.SELECT_MODULE || hit.action == Action.TOGGLE_MODULE)
                        && inside(x, y, hit.x, hit.y, hit.right, hit.bottom)) {
                    selected = results.get(hit.data);
                    Runnable configure = selected.module.configure();
                    if (configure != null) configure.run();
                    else showSection(OPTIONS);
                    return true;
                }
            }
            return false;
        }
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        for (int i = hitCount - 1; i >= 0; i--) {
            Hit hit = hits[i];
            if (!inside(x, y, hit.x, hit.y, hit.right, hit.bottom)) continue;
            activate(hit, x);
            return true;
        }
        return false;
    }

    private void activate(Hit hit, double x) {
        switch (hit.action) {
            case NAVIGATE -> navigate(NAV[hit.data]);
            case PAUSE -> PauseManager.pause(minecraft);
            case FAVORITES -> { favoritesOnly = !favoritesOnly; filter(); }
            case LANGUAGE -> {
                HudSettings.setLanguage(LANGUAGES[(HudSettings.language().ordinal() + 1) % LANGUAGES.length]);
                search.setHint(searchLabel());
                filter();
            }
            case THEME -> DashAppearance.cyclePreset();
            case FULLSCREEN -> {
                minecraft.getWindow().toggleFullScreen();
                minecraft.options.fullscreen().set(minecraft.getWindow().isFullscreen());
                minecraft.options.save();
            }
            case FOCUS_SEARCH -> focusSearch();
            case COMPACT_PAGE -> showSection(hit.data);
            case SELECT_MODULE -> {
                selected = results.get(hit.data);
                showSection(OPTIONS);
            }
            case STAR_MODULE -> {
                DashAppearance.toggleFavorite(results.get(hit.data).module.id());
                if (favoritesOnly) filter();
            }
            case TOGGLE_MODULE -> {
                selected = results.get(hit.data);
                selected.module.toggle().run();
            }
            case CONFIGURE -> {
                if (selected != null && selected.module.configure() != null) selected.module.configure().run();
            }
            case KEY_CAPTURE -> {
                if (selected != null) captureKey = bindingFor(selected.module);
            }
            case TRIGGER_TARGET -> { TriggerBot.toggleTarget(TARGETS[hit.data]); TriggerBot.saveSettings(); }
            case TRIGGER_RANGE, VELOCITY, HUD_OPACITY, HUD_SCALE -> {
                dragging = hit.action;
                dragLeft = hit.x;
                dragRight = hit.right - 1;
                dragTo(x);
            }
            case HUD_EDITOR -> minecraft.setScreen(new DashHudEditor(this));
            case EDIT_ELEMENT -> minecraft.setScreen(new DashHudEditor(this, ELEMENTS[hit.data]));
            case TOGGLE_ELEMENT -> DashHudLayout.toggle(ELEMENTS[hit.data]);
        }
    }

    private void navigate(Nav nav) {
        switch (nav) {
            case CONFIGS, FRIENDS, WAYPOINTS -> minecraft.setScreen(new DashLibraryScreen(this, nav.key));
            case SETTINGS -> minecraft.setScreen(new DashAppearanceScreen(this));
            default -> {
                category = nav;
                showSection(MODULES);
                search.setValue("");
                filter();
            }
        }
    }

    private void showSection(int section) {
        compactSection = section;
        hitCount = 0;
        changedAt = System.nanoTime();
    }

    private void focusSearch() {
        if (compactSection != MODULES) showSection(MODULES);
        setFocused(search);
        search.setCursorPosition(search.getValue().length());
    }

    private static Component searchLabel() {
        return Component.literal(text("Пошук модулів", "Поиск модулей", "Search modules"));
    }

    private void dragTo(double x) {
        float amount = Mth.clamp((float) (x - dragLeft) / Math.max(1, dragRight - dragLeft), 0, 1);
        switch (dragging) {
            case TRIGGER_RANGE -> TriggerBot.setRange(Mth.lerp(amount, TriggerBot.MIN_RANGE, TriggerBot.MAX_RANGE));
            case VELOCITY -> Velocity.setForce(amount);
            case HUD_OPACITY -> HudSettings.setOpacitySlider(amount);
            case HUD_SCALE -> HudSettings.setScaleSlider(amount);
            default -> {}
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging != null) {
            dragTo(localX(event.x()));
            return true;
        }
        if (getFocused() == search) return search.mouseDragged(
                new MouseButtonEvent(localX(event.x()), localY(event.y()), event.buttonInfo()), dx / scale, dy / scale);
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging = null;
            TriggerBot.saveSettings();
            HudSettings.save();
            return true;
        }
        return super.mouseReleased(new MouseButtonEvent(localX(event.x()), localY(event.y()), event.buttonInfo()));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0 || closeAt != 0) return false;
        double x = localX(mouseX), y = localY(mouseY);
        int direction = scrollY > 0 ? -1 : 1;
        if (compactSection == MODULES && inside(x, y, contentX, moduleListY, contentX + contentWidth,
                moduleListY + moduleListHeight)) {
            moduleOffset = Mth.clamp(moduleOffset + 2 * direction, 0,
                    Math.max(0, results.size() - Math.max(1, moduleListHeight / moduleRowStep)));
            return true;
        }
        if (compactSection == HUD && inside(x, y, contentX, elementsListY, contentX + contentWidth,
                elementsListY + elementsListHeight)) {
            hudOffset = Mth.clamp(hudOffset + 2 * direction, 0,
                    Math.max(0, ELEMENTS.length - Math.max(1, elementsListHeight / hudRowStep)));
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (closeAt != 0) return true;
        if (captureKey != null) {
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE) {
                InventoryBindSupport.unbind(minecraft, captureKey);
            } else if (event.key() != GLFW.GLFW_KEY_ESCAPE && !InventoryBindSupport.assign(minecraft, captureKey, event)) {
                DashHudLayout.notify(text("Клавіша зайнята", "Клавиша занята", "Key unavailable"),
                        text("Вибери іншу клавішу", "Выбери другую клавишу", "Choose another key"));
                return true;
            }
            captureKey = null;
            cachedKey = null;
            return true;
        }
        if (DashClientClient.dashboardKey(event)) {
            onClose();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_K && (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) {
            focusSearch();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { if (closeAt == 0) closeAt = System.nanoTime(); }
    @Override
    public void removed() {
        if (!SaveMode.isPaused()) {
            HudSettings.save();
            TriggerBot.saveSettings();
        }
    }

    boolean compactLayout() { return true; }
    int visibleModules() { return results.size(); }
    private double localX(double x) { return (x - panelLeft) / scale; }
    private double localY(double y) { return (y - panelTop) / scale; }

    private static boolean inside(double x, double y, int left, int top, int right, int bottom) {
        return x >= left && x < right && y >= top && y < bottom;
    }

    enum Icon { DASH, GRID, COMBAT, MOVE, PLAYER, RENDER, VISUAL, CLIENT, CONFIG, FRIEND, PIN, SLIDERS,
        SEARCH, STAR, LANGUAGE, PALETTE, FULL, PREVIEW, ELEMENTS, PAUSE }

    private enum Nav {
        ALL("All", "Усе", "Все", Icon.GRID),
        COMBAT("Combat", "Бій", "Бой", Icon.COMBAT),
        MOVEMENT("Movement", "Рух", "Движение", Icon.MOVE),
        PLAYER("Player", "Гравець", "Игрок", Icon.PLAYER),
        RENDER("Render", "Рендер", "Рендер", Icon.RENDER),
        VISUAL("Visual", "Візуал", "Визуал", Icon.VISUAL),
        CLIENT("Client", "Клієнт", "Клиент", Icon.CLIENT),
        CONFIGS("Configs", "Конфіги", "Конфиги", Icon.CONFIG),
        FRIENDS("Friends", "Друзі", "Друзья", Icon.FRIEND),
        WAYPOINTS("Waypoints", "Мітки", "Метки", Icon.PIN),
        SETTINGS("Settings", "Параметри", "Настройки", Icon.SLIDERS);

        private final String key, ukrainian, russian;
        private final Icon icon;

        Nav(String key, String ukrainian, String russian, Icon icon) {
            this.key = key;
            this.ukrainian = ukrainian;
            this.russian = russian;
            this.icon = icon;
        }

        String label() { return text(ukrainian, russian, key); }
    }

    private enum Action { NAVIGATE, PAUSE, FAVORITES, LANGUAGE, THEME, FULLSCREEN, FOCUS_SEARCH, COMPACT_PAGE,
        SELECT_MODULE, STAR_MODULE, TOGGLE_MODULE, CONFIGURE, KEY_CAPTURE, TRIGGER_TARGET, TRIGGER_RANGE, VELOCITY,
        HUD_OPACITY, HUD_SCALE, HUD_EDITOR, EDIT_ELEMENT, TOGGLE_ELEMENT }

    private static final class ModuleRow {
        private final DashClientClient.DashboardModule module;
        private float hover, toggle;
        private ModuleRow(DashClientClient.DashboardModule module) {
            this.module = module;
            toggle = module.enabled().getAsBoolean() ? 1 : 0;
        }
    }

    private static final class Hit {
        Action action;
        int data, x, y, right, bottom;
    }

    private record Rect(int x, int y, int width, int height) {
        int right() { return x + width; }
    }
}
