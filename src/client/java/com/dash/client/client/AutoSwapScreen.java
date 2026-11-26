package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class AutoSwapScreen extends Screen {
    private final Screen parent;
    private List<AutoSwap.Choice> choices;
    private int left, top, panelWidth;

    AutoSwapScreen(Screen parent) {
        super(Component.literal("AutoSwap"));
        this.parent = parent;
    }

    @Override protected void init() {
        panelWidth = Math.min(352, width - 16);
        left = (width - panelWidth) / 2;
        top = Math.max(4, (height - 206) / 2);
        choices = AutoSwap.choices(minecraft);
        addRenderableWidget(Button.builder(label(false), button -> cycle(false, button))
                .bounds(left + 12, top + 63, panelWidth - 24, 20).build());
        addRenderableWidget(Button.builder(label(true), button -> cycle(true, button))
                .bounds(left + 12, top + 91, panelWidth - 24, 20).build());
        addRenderableWidget(Button.builder(runLabel(), button -> {
            AutoSwap.toggle();
            button.setMessage(runLabel());
        }).bounds(left + 12, top + 119, panelWidth - 24, 20).build());
    }

    private Component runLabel() {
        return Component.literal(AutoSwap.isEnabled() ? text("AutoSwap: увімкнено", "AutoSwap: включён", "AutoSwap: on")
                : text("AutoSwap: вимкнено", "AutoSwap: выключен", "AutoSwap: off"));
    }

    private Component label(boolean defense) {
        String key = AutoSwap.preference(defense);
        String automatic = text("Автоматично за бонусами", "Автоматически по бонусам", "Automatic by bonuses");
        String name = key.isEmpty() ? automatic : choices.stream().filter(choice -> choice.key().equals(key)).findFirst()
                .map(AutoSwap.Choice::label).orElse(automatic);
        String prefix = defense ? text("Захист: ", "Защита: ", "Defense: ") : text("Атака: ", "Атака: ", "Attack: ");
        return Component.literal(prefix + font.plainSubstrByWidth(name, panelWidth - 90));
    }

    private void cycle(boolean defense, Button button) {
        int index = 0;
        for (int i = 0; i < choices.size(); i++) if (choices.get(i).key().equals(AutoSwap.preference(defense))) index = i;
        AutoSwap.select(defense, choices.get((index + 1) % choices.size()).key());
        button.setMessage(label(defense));
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x99080B14);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fillGradient(left, top, left + panelWidth, top + 206, HudSettings.shade(.03F), HudSettings.shade(.13F));
        graphics.fill(left, top, left + panelWidth, top + 2, HudSettings.accent());
        graphics.drawString(font, title, left + 12, top + 13, 0xFFF2EEFF, false);
        line(graphics, text("Обери сферу або талісман для лівої руки.", "Выбери сферу или талисман для левой руки.",
                "Choose an orb or talisman for your offhand."), 35);
        line(graphics, text("Авто: порівняння бонусів сфер і талісманів.", "Авто: сравнение бонусов сфер и талисманов.",
                "Auto: compare orb and talisman bonuses."), 47);
        line(graphics, text("Зняв останнє жовте серце чарки → атака.", "Снял последнее жёлтое сердце чарки → атака.",
                "Deplete the last absorption heart → attack."), 150);
        line(graphics, text("Отримав удар → одразу захист на 1,5 с.", "Получил удар → сразу защита на 1,5 с.",
                "Take a hit → switch to defense for 1.5 s."), 163);
        line(graphics, AutoSwap.status(), 185);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void line(GuiGraphics graphics, String value, int y) {
        graphics.drawString(font, font.plainSubstrByWidth(value, panelWidth - 24), left + 12, top + y, 0xFFD6CEE5, false);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
