package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

final class AutoSellScreen extends Screen {
    private final Screen parent;
    private String itemText = AutoSell.itemInput();
    private String searchText = AutoSell.searchInput();
    private EditBox item;
    private EditBox search;
    private Button runButton;
    private int left;
    private int top;
    private int panelWidth;

    AutoSellScreen(Screen parent) {
        super(Component.literal("AutoSell"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(352, width - 16);
        left = (width - panelWidth) / 2;
        top = Math.max(4, (height - 176) / 2);
        int fieldWidth = panelWidth - 24;
        item = addRenderableWidget(field(text("Предмет", "Предмет", "Item"), itemText, "minecraft:diamond", top + 52, fieldWidth));
        search = addRenderableWidget(field(text("Пошук", "Поиск", "Search"), searchText, "Алмаз", top + 94, fieldWidth));
        item.setResponder(value -> itemText = value);
        search.setResponder(value -> searchText = value);
        int buttonWidth = (fieldWidth - 8) / 2;
        addRenderableWidget(Button.builder(Component.literal(text("Предмет із руки", "Предмет из руки", "Use held item")),
                        button -> useHeldItem())
                .bounds(left + 12, top + 122, buttonWidth, 20).build());
        runButton = addRenderableWidget(Button.builder(runLabel(), button -> runOrStop())
                .bounds(left + 20 + buttonWidth, top + 122, buttonWidth, 20).build());
        setInitialFocus(item);
    }

    private EditBox field(String label, String value, String hint, int y, int fieldWidth) {
        EditBox box = new EditBox(font, left + 12, y, fieldWidth, 20, Component.literal(label));
        box.setMaxLength(96);
        box.setHint(Component.literal(hint));
        box.setValue(value);
        return box;
    }

    private void useHeldItem() {
        if (minecraft.player == null) return;
        ItemStack held = minecraft.player.getMainHandItem();
        if (held.isEmpty()) return;
        item.setValue(BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
        search.setValue(AutoSell.defaultSearch(held));
    }

    private void runOrStop() {
        if (AutoSell.isEnabled()) {
            AutoSell.stop(text("Зупинено", "Остановлено", "Stopped"));
            runButton.setMessage(runLabel());
        } else {
            AutoSell.start(minecraft, item.getValue(), search.getValue());
        }
    }

    private static Component runLabel() {
        return Component.literal(AutoSell.isEnabled() ? text("Зупинити", "Остановить", "Stop")
                : text("Почати продаж", "Начать продажу", "Start selling"));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x99080B14);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fillGradient(left, top, left + panelWidth, top + 176, HudSettings.shade(.03F), HudSettings.shade(.13F));
        graphics.fill(left, top, left + panelWidth, top + 2, HudSettings.accent());
        graphics.drawString(font, title, left + 12, top + 13, 0xFFF2EEFF, false);
        drawLine(graphics, text("Предмет · ID або назва в інвентарі", "Предмет · ID или название в инвентаре",
                "Item · ID or inventory name"), 39);
        drawLine(graphics, text("Назва для /ah search · якщо відрізняється", "Название для /ah search · если отличается",
                "Name for /ah search · if different"), 81);
        drawLine(graphics, AutoSell.status(), 154);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawLine(GuiGraphics graphics, String value, int y) {
        graphics.drawString(font, font.plainSubstrByWidth(value, panelWidth - 24),
                left + 12, top + y, 0xFFD6CEE5, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
