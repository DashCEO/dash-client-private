package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import com.dash.client.client.InventoryBindSupport.ItemChoice;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

abstract class InventoryBindSettingsScreen extends Screen implements DashBindInput.KeyCapture {
    private static final int PANEL_HEIGHT = 231;
    private final Screen parent;
    protected int left;
    protected int top;
    protected int panelWidth;
    protected int selected;
    protected String status = "";
    private boolean capturing;
    private Button clearKeyButton;

    InventoryBindSettingsScreen(String title, Screen parent) {
        super(Component.literal(title));
        this.parent = parent;
    }

    @Override protected final void init() {
        panelWidth = Math.min(348, width - 12);
        left = (width - panelWidth) / 2;
        top = (height - PANEL_HEIGHT) / 2;
        int inner = panelWidth - 24;
        int half = (inner - 6) / 2;
        int right = 12 + half + 6;
        int count = InventoryBindSupport.RULE_COUNT;
        int keyWidth = (inner - 3 * (count - 1)) / count;
        for (int i = 0; i < count; i++) {
            int index = i;
            String marker = (i == selected ? "›" : "") + (i + 1) + (configured(i) ? "*" : "");
            button(marker, 12 + i * (keyWidth + 3), 29, keyWidth, () -> selectRule(index));
        }
        button(addLabel(), 12, 53, half, this::addRule);
        button(deleteLabel(), right, 53, half, this::deleteSelected);
        initRuleControls(inner, half, right);

        String keyName = key(selected).getTranslatedKeyMessage().getString();
        String bindLabel = capturing ? text("Вибір бінда", "Выбор бинда", "Choosing bind")
                : text("Клавіша · ", "Клавиша · ", "Key · ") + keyName;
        button(fit(bindLabel, half), 12, 153, half, () -> {
            capturing = true;
            status = text("Натисни клавішу або кнопку миші.", "Нажми клавишу или кнопку мыши.", "Press a key or mouse button.");
            rebuildWidgets();
        });
        clearKeyButton = button(capturing ? text("Скасувати", "Отмена", "Cancel")
                : text("Зняти бінд", "Убрать бинд", "Clear key"), right, 153, half, this::clearOrCancel);
        button(isEnabled() ? text("Увімкнено", "Включено", "Enabled")
                : text("Вимкнено", "Выключено", "Disabled"), 12, 179, half, () -> {
                    toggle();
                    rebuildWidgets();
                });
        button(text("Назад", "Назад", "Back"), right, 179, half, this::onClose);
    }

    protected abstract void initRuleControls(int inner, int half, int right);
    protected abstract KeyMapping key(int index);
    protected abstract boolean configured(int index);
    protected abstract boolean empty(int index);
    protected abstract boolean deleteRule(int index);
    protected abstract String addLabel();
    protected abstract String deleteLabel();
    protected abstract boolean isEnabled();
    protected abstract void toggle();
    protected abstract void chooseNewItem();
    protected abstract String hint();

    protected void ruleSelected() {}
    protected void drawRuleLabels(GuiGraphics graphics) {}

    protected final Button button(String label, int x, int y, int width, Runnable onPress) {
        return addRenderableWidget(Button.builder(Component.literal(label), button -> onPress.run())
                .bounds(left + x, top + y, width, 20).build());
    }

    protected final String fit(String label, int width) {
        return font.plainSubstrByWidth(label, Math.max(0, width - 8));
    }

    protected final String itemName(ItemChoice item) {
        return item == null ? text("Обрати предмет", "Выбрать предмет", "Choose item") : item.label();
    }

    protected final void openPicker(Consumer<ItemChoice> selection) {
        capturing = false;
        if (minecraft.player == null) {
            status = text("Відкрий світ, щоб обрати предмет.", "Открой мир, чтобы выбрать предмет.", "Open a world to choose items.");
            rebuildWidgets();
            return;
        }
        minecraft.setScreen(new InventoryItemPicker(this, selection));
    }

    private void selectRule(int index) {
        selected = index;
        capturing = false;
        status = "";
        ruleSelected();
        rebuildWidgets();
    }

    private void addRule() {
        for (int i = 0; i < InventoryBindSupport.RULE_COUNT; i++) {
            if (empty(i) && key(i).isUnbound()) {
                selectRule(i);
                chooseNewItem();
                return;
            }
        }
        status = text("Усі місця зайняті.", "Все места заняты.", "All rule slots are in use.");
    }

    private void deleteSelected() {
        capturing = false;
        status = deleteRule(selected) ? text("Видалено.", "Удалено.", "Deleted.") : saveError();
        ruleSelected();
        rebuildWidgets();
    }

    private void clearOrCancel() {
        if (!capturing) InventoryBindSupport.unbind(minecraft, key(selected));
        capturing = false;
        status = "";
        rebuildWidgets();
    }

    protected final String saveError() {
        return text("Не вдалося зберегти.", "Не удалось сохранить.", "Could not save.");
    }

    @Override public final boolean keyPressed(KeyEvent event) {
        return captureDashKey(event) || super.keyPressed(event);
    }

    @Override public final boolean captureDashKey(KeyEvent event) {
        if (!capturing) return false;
        return capture(InputConstants.getKey(event));
    }

    @Override public final boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!capturing || event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && clearKeyButton.isMouseOver(event.x(), event.y())) {
            return super.mouseClicked(event, doubleClick);
        }
        return capture(InputConstants.Type.MOUSE.getOrCreate(event.button()));
    }

    private boolean capture(InputConstants.Key captured) {
        if (InventoryBindSupport.assign(minecraft, key(selected), captured)) {
            capturing = false;
            status = "";
        } else {
            status = text("Цей бінд уже використовується в Dash.", "Этот бинд уже используется в Dash.", "This bind is already in use by Dash.");
        }
        rebuildWidgets();
        return true;
    }

    @Override public final void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x99080B14);
    }

    @Override public final void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fillGradient(left, top, left + panelWidth, top + PANEL_HEIGHT, HudSettings.shade(.03F), HudSettings.shade(.13F));
        graphics.fill(left, top, left + panelWidth, top + 2, HudSettings.accent());
        graphics.drawString(font, title, left + 12, top + 13, 0xFFF2EEFF, false);
        drawRuleLabels(graphics);
        graphics.drawString(font, fit(status.isEmpty() ? hint() : status, panelWidth - 20),
                left + 12, top + 211, 0xFFD6CEE5, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public final boolean isPauseScreen() { return false; }
    @Override public final void onClose() { minecraft.setScreen(parent); }
}
