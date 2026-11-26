package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

final class MarksScreen extends Screen implements DashBindInput.KeyCapture {
    private static final int HEIGHT = 174;
    private final Screen parent;
    private EditBox colorInput;
    private Button keyButton;
    private Button clearKeyButton;
    private Button removeButton;
    private Button clearButton;
    private int size = Marks.size();
    private int opacity = Marks.opacity();
    private int left;
    private int top;
    private int panelWidth;
    private boolean binding;
    private String status = "";

    MarksScreen(Screen parent) {
        super(Component.literal("Marks"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(328, width - 8);
        left = (width - panelWidth) / 2;
        top = Math.max(2, (height - HEIGHT) / 2);
        int contentWidth = panelWidth - 20;
        int half = (contentWidth - 6) / 2;
        int x = left + 10;
        int right = x + half + 6;
        binding = false;

        String previousColor = colorInput == null ? String.format(Locale.ROOT, "#%06X", Marks.color()) : colorInput.getValue();
        colorInput = addRenderableWidget(new EditBox(font, x + 58, top + 40, 73, 18,
                Component.literal(text("Код кольору", "Код цвета", "Color code"))));
        colorInput.setMaxLength(7);
        colorInput.setFilter(value -> value.matches("#?[a-fA-F0-9]{0,6}"));
        colorInput.setValue(previousColor);

        keyButton = addRenderableWidget(Button.builder(keyLabel(), button -> {
            binding = true;
            status = text("Натисни клавішу або кнопку миші.", "Нажми клавишу или кнопку мыши.", "Press a key or mouse button.");
            updateBindButtons();
        }).bounds(x, top + 64, half, 20).build());
        clearKeyButton = addRenderableWidget(Button.builder(clearKeyLabel(), button -> {
            if (!binding) InventoryBindSupport.unbind(minecraft, Marks.key());
            binding = false;
            status = "";
            updateBindButtons();
        }).bounds(right, top + 64, half, 20).build());
        addRenderableWidget(slider(x, top + 88, half, text("Розмір", "Размер", "Size"),
                Marks.MIN_SIZE, Marks.MAX_SIZE, size, value -> size = value));
        addRenderableWidget(slider(right, top + 88, half, text("Прозорість", "Прозрачность", "Opacity"),
                Marks.MIN_OPACITY, Marks.MAX_OPACITY, opacity, value -> opacity = value));

        removeButton = addRenderableWidget(Button.builder(Component.literal(text("Видалити останню",
                        "Удалить последнюю", "Remove last")), button -> {
                    status = Marks.removeLast(minecraft) ? text("Останню мітку видалено.", "Последняя метка удалена.",
                            "Last mark removed.") : saveFailed();
                    updateActionButtons();
                }).bounds(x, top + 112, half, 20).build());
        clearButton = addRenderableWidget(Button.builder(Component.literal(text("Очистити всі", "Очистить все", "Clear all")),
                button -> confirmClear()).bounds(right, top + 112, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal(text("Зберегти стиль", "Сохранить стиль", "Save style")),
                button -> saveStyle()).bounds(x, top + 136, half, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(right, top + 136, half, 20).build());
        updateActionButtons();
    }

    private void updateActionButtons() {
        boolean hasMarks = Marks.count(minecraft) > 0 && !SaveMode.isPaused();
        removeButton.active = hasMarks;
        clearButton.active = hasMarks;
    }

    @Override
    public void tick() {
        updateActionButtons();
    }

    private void confirmClear() {
        var level = minecraft.level;
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed && minecraft.level == level && !SaveMode.isPaused()) {
                status = Marks.clear(minecraft) ? text("Усі крижані мітки видалено.", "Все ледяные метки удалены.",
                        "All ice marks removed.") : saveFailed();
            }
            minecraft.setScreen(this);
        }, Component.literal(text("Видалити всі крижані мітки?", "Удалить все ледяные метки?", "Clear all ice marks?")),
                Component.literal(text("Зміни стосуються поточного виміру.", "Изменения действуют в текущем измерении.",
                        "This affects the current dimension."))));
    }

    private void saveStyle() {
        int color = draftColor();
        if (color < 0) {
            status = text("Введи 6 шістнадцяткових цифр.", "Введи 6 шестнадцатеричных цифр.", "Enter six hex digits.");
            return;
        }
        status = Marks.applyStyle(color, size, opacity)
                ? text("Стиль збережено.", "Стиль сохранён.", "Style saved.") : saveFailed();
    }

    private String saveFailed() {
        return text("Не вдалося зберегти зміни.", "Не удалось сохранить изменения.", "Could not save changes.");
    }

    private int draftColor() {
        String value = colorInput.getValue();
        if (value.startsWith("#")) value = value.substring(1);
        return value.length() == 6 ? Integer.parseInt(value, 16) : -1;
    }

    private Component keyLabel() {
        if (binding) {
            return Component.literal(text("Вибір бінда", "Выбор бинда", "Choosing bind"));
        }
        return Component.literal(text("Мітка · ", "Метка · ", "Mark · "))
                .append(Marks.key().getTranslatedKeyMessage());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return captureDashKey(event) || super.keyPressed(event);
    }

    @Override
    public boolean captureDashKey(KeyEvent event) {
        if (!binding) return false;
        return capture(InputConstants.getKey(event));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!binding || event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && clearKeyButton.isMouseOver(event.x(), event.y())) {
            return super.mouseClicked(event, doubleClick);
        }
        return capture(InputConstants.Type.MOUSE.getOrCreate(event.button()));
    }

    private boolean capture(InputConstants.Key captured) {
        if (!InventoryBindSupport.assign(minecraft, Marks.key(), captured)) {
            status = text("Цей бінд уже використовується в Dash.", "Этот бинд уже используется в Dash.", "This bind is already in use by Dash.");
            return true;
        }
        status = text("Бінд збережено.", "Бинд сохранён.", "Bind saved.");
        binding = false;
        updateBindButtons();
        return true;
    }

    private void updateBindButtons() {
        keyButton.setMessage(keyLabel());
        clearKeyButton.setMessage(clearKeyLabel());
    }

    private Component clearKeyLabel() {
        return Component.literal(binding ? text("Скасувати", "Отмена", "Cancel")
                : text("Зняти бінд", "Убрать бинд", "Clear key"));
    }

    private static AbstractSliderButton slider(int x, int y, int width, String label,
                                               int min, int max, int value, IntConsumer change) {
        return new AbstractSliderButton(x, y, width, 20, Component.literal(label + " · " + value + "%"),
                (value - min) / (double) (max - min)) {
            private int percent() {
                return min + (int) Math.round(this.value * (max - min));
            }

            @Override protected void updateMessage() {
                setMessage(Component.literal(label + " · " + percent() + "%"));
            }

            @Override protected void applyValue() {
                change.accept(percent());
            }
        };
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x99080B14);
        graphics.fillGradient(left, top, left + panelWidth, top + HEIGHT, HudSettings.shade(.03F), HudSettings.shade(.13F));
        graphics.fill(left, top, left + panelWidth, top + 2, HudSettings.accent());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, text("Крижані мітки", "Ледяные метки", "Ice Marks"),
                left + 10, top + 10, 0xFFF2EEFF, false);
        String total = text("У вимірі", "В измерении", "In this dimension") + " · " + Marks.count(minecraft);
        graphics.drawString(font, total, left + 10, top + 24, 0xFFD6CEE5, false);
        graphics.drawString(font, text("Колір", "Цвет", "Color"), left + 10, top + 45, 0xFFD6CEE5, false);
        int previewColor = draftColor();
        Marks.drawCrystal(graphics, left + panelWidth - 34, top + 31, Marks.radius(size),
                previewColor < 0 ? Marks.color() : previewColor, opacity, Marks.pulse());
        String footer = InventoryBindSupport.conflicts(minecraft, Marks.key()) && !binding
                ? text("Клавіша конфліктує з іншим біндом.", "Клавиша конфликтует с другим биндом.", "Key conflicts with another binding.")
                : status;
        graphics.drawString(font, font.plainSubstrByWidth(footer, panelWidth - 20),
                left + 10, top + 161, 0xFFD6CEE5, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
