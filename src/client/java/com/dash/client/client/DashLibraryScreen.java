package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

final class DashLibraryScreen extends Screen {
    private static final int INSET = 14;
    private static final int ROW_HEIGHT = 22;
    private static final int WHITE = 0xFFF2F5FA;
    private static final int MUTED = 0xFF9DA8B8;

    private final Screen parent;
    private final Page page;
    private Font ui;
    private EditBox field;
    private List<Entry> entries = List.of();
    private String status = "";
    private String input = "";
    private int selected = -1;
    private int scroll;
    private int left;
    private int top;
    private int right;
    private int bottom;

    DashLibraryScreen(Screen parent, String page) {
        super(Component.literal(page));
        this.parent = parent;
        this.page = Page.valueOf(page.toUpperCase(Locale.ROOT));
    }

    @Override
    protected void init() {
        ui = DashTypography.font(font);
        int panelWidth = Math.min(440, width - 12);
        int panelHeight = Math.min(272, height - 10);
        left = (width - panelWidth) / 2;
        right = left + panelWidth;
        top = (height - panelHeight) / 2;
        bottom = top + panelHeight;
        field = addWidget(new EditBox(ui, left + INSET + 7, top + 38,
                panelWidth - INSET * 2 - 14, 16, Component.literal(page.hint())));
        field.setBordered(false);
        field.setMaxLength(page == Page.FRIENDS ? 16 : 48);
        field.setValue(input);
        field.setHint(Component.literal(page.hint()));
        field.setResponder(value -> input = value);
        setInitialFocus(field);
        refresh();
    }

    private void refresh() {
        try {
            entries = switch (page) {
                case CONFIGS -> DashProfiles.names().stream().map(name -> new Entry(name, "")).toList();
                case FRIENDS -> Friends.names().stream().map(name -> new Entry(name, "")).toList();
                case WAYPOINTS -> DashWaypoints.current(minecraft).stream()
                        .map(marker -> new Entry(marker.name(), marker.x() + " / " + marker.y() + " / " + marker.z())).toList();
            };
        } catch (IOException | UncheckedIOException exception) {
            entries = List.of();
            status = text("Не вдалося прочитати список.", "Не удалось прочитать список.", "Could not read the list.");
        }
        selected = -1;
        scroll = Math.max(0, Math.min(scroll, entries.size() - capacity()));
    }

    private int capacity() {
        return Math.max(0, (bottom - 59 - (top + 58)) / ROW_HEIGHT);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xA4080B10);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        DashShape.frame(graphics, left, top, right, bottom, 12, 0xFF343A45, DashAppearance.background());
        graphics.drawString(ui, page.label(), left + INSET, top + 13, WHITE, false);
        actionLabel(graphics, right - INSET - 44, top + 8, right - INSET, top + 27,
                text("Назад", "Назад", "Back"), mouseX, mouseY);
        DashShape.frame(graphics, left + INSET, top + 32, right - INSET, top + 53, 5, 0xFF3D4654, 0xFF171C24);
        if (entries.isEmpty()) {
            String empty = page == Page.WAYPOINTS && minecraft.level == null
                    ? text("Відкрий світ, щоб керувати мітками.", "Открой мир, чтобы управлять метками.", "Open a world to manage waypoints.")
                    : text("Список порожній", "Список пуст", "No entries");
            graphics.drawString(ui, DashHud.fit(ui, empty, right - left - INSET * 2), left + INSET, top + 65, MUTED, false);
        }
        for (int row = 0; row < capacity() && scroll + row < entries.size(); row++) {
            int index = scroll + row;
            int y = top + 58 + row * ROW_HEIGHT;
            var entry = entries.get(index);
            boolean hover = inside(mouseX, mouseY, left + INSET, y, right - INSET, y + ROW_HEIGHT - 3);
            DashShape.fill(graphics, left + INSET, y, right - INSET, y + ROW_HEIGHT - 3, 5,
                    selected == index ? 0xFF273644 : hover ? 0xFF252B34 : 0xFF181D25);
            int end = right - INSET - 8;
            int detailWidth = entry.detail().isEmpty() ? 0 : Math.min(ui.width(entry.detail()), (right - left) / 3);
            graphics.drawString(ui, DashHud.fit(ui, entry.name(), end - detailWidth - left - INSET - 12),
                    left + INSET + 8, y + 5, WHITE, false);
            if (detailWidth > 0) {
                graphics.drawString(ui, DashHud.fit(ui, entry.detail(), detailWidth), end - detailWidth, y + 5, MUTED, false);
            }
        }

        int actionWidth = (right - left - INSET * 2 - (page.actions.length - 1) * 5) / page.actions.length;
        for (int i = 0; i < page.actions.length; i++) {
            int x = left + INSET + i * (actionWidth + 5);
            actionLabel(graphics, x, bottom - 48, x + actionWidth, bottom - 26, page.actions[i].label(), mouseX, mouseY);
        }
        graphics.drawString(ui, DashHud.fit(ui, status, right - left - INSET * 2),
                left + INSET, bottom - 17, DashAppearance.accent(), false);
        super.render(graphics, mouseX, mouseY, delta);
    }

    private void actionLabel(GuiGraphics graphics, int x, int y, int end, int lower, String label, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, end, lower);
        DashShape.frame(graphics, x, y, end, lower, 6,
                hover ? DashAppearance.accent() : 0xFF3D4654, hover ? 0xFF2A3442 : 0xFF1B222C);
        String clipped = DashHud.fit(ui, label, end - x - 4);
        graphics.drawString(ui, clipped, x + (end - x - ui.width(clipped)) / 2, y + (lower - y - 9) / 2, WHITE, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        double x = event.x(), y = event.y();
        if (inside(x, y, right - INSET - 44, top + 8, right - INSET, top + 27)) {
            onClose();
            return true;
        }
        if (inside(x, y, left + INSET, top + 58, right - INSET, top + 58 + capacity() * ROW_HEIGHT)) {
            int index = scroll + (int) ((y - top - 58) / ROW_HEIGHT);
            if (index < entries.size()) {
                selected = index;
                field.setValue(entries.get(index).name());
            }
            return true;
        }
        int actionWidth = (right - left - INSET * 2 - (page.actions.length - 1) * 5) / page.actions.length;
        for (int i = 0; i < page.actions.length; i++) {
            int start = left + INSET + i * (actionWidth + 5);
            if (inside(x, y, start, bottom - 48, start + actionWidth, bottom - 26)) {
                perform(page.actions[i]);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!inside(x, y, left + INSET, top + 58, right - INSET, bottom - 54) || vertical == 0) return false;
        scroll = Math.max(0, Math.min(scroll + (vertical > 0 ? -1 : 1), entries.size() - capacity()));
        return true;
    }

    private void perform(Action action) {
        String value = field.getValue().strip();
        boolean replacesProfile = page == Page.CONFIGS && action == Action.SAVE
                && entries.stream().anyMatch(entry -> entry.name().equals(value));
        if (!value.isEmpty() && (action == Action.REMOVE || replacesProfile)) {
            String title = action == Action.REMOVE ? text("Видалити запис", "Удалить запись", "Delete entry")
                    : text("Перезаписати профіль", "Перезаписать профиль", "Replace profile");
            minecraft.setScreen(new ConfirmScreen(confirmed -> {
                if (confirmed) apply(action, value);
                minecraft.setScreen(this);
            }, Component.literal(title), Component.literal(value)));
        } else {
            apply(action, value);
        }
    }

    private void apply(Action action, String value) {
        status = switch (page) {
            case CONFIGS -> DashProfiles.manage(minecraft, action == Action.REMOVE ? "delete" : action.verb, value);
            case FRIENDS -> Friends.manage(action.verb, value);
            case WAYPOINTS -> DashWaypoints.manage(minecraft, action.verb, value);
        };
        refresh();
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }

    private static boolean inside(double x, double y, int left, int top, int right, int bottom) {
        return x >= left && x < right && y >= top && y < bottom;
    }

    private record Entry(String name, String detail) {}

    private enum Page {
        CONFIGS("Профілі", "Профили", "Configs", Action.SAVE, Action.LOAD, Action.REMOVE),
        FRIENDS("Друзі", "Друзья", "Friends", Action.ADD, Action.REMOVE),
        WAYPOINTS("Точки маршруту", "Точки маршрута", "Waypoints", Action.ADD, Action.RANGE, Action.REMOVE);

        final String ua;
        final String ru;
        final String en;
        final Action[] actions;

        Page(String ua, String ru, String en, Action... actions) {
            this.ua = ua;
            this.ru = ru;
            this.en = en;
            this.actions = actions;
        }

        String label() { return text(ua, ru, en); }

        String hint() {
            return switch (this) {
                case CONFIGS -> text("Назва профілю", "Название профиля", "Profile name");
                case FRIENDS -> text("Нік Minecraft", "Ник Minecraft", "Minecraft username");
                case WAYPOINTS -> text("Назва мітки або дальність", "Имя метки или дальность", "Waypoint name or distance");
            };
        }
    }

    private enum Action {
        SAVE("save", "Зберегти", "Сохранить", "Save"),
        LOAD("load", "Завантажити", "Загрузить", "Load"),
        ADD("add", "Додати", "Добавить", "Add"),
        RANGE("range", "Дальність", "Дальность", "Distance"),
        REMOVE("remove", "Видалити", "Удалить", "Delete");

        final String verb;
        final String ua;
        final String ru;
        final String en;

        Action(String verb, String ua, String ru, String en) {
            this.verb = verb;
            this.ua = ua;
            this.ru = ru;
            this.en = en;
        }

        String label() { return text(ua, ru, en); }
    }
}
