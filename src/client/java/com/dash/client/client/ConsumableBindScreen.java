package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import com.dash.client.client.ConsumableBind.Rule;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ConsumableBindScreen extends InventoryBindSettingsScreen {
    private String delayDraft;

    ConsumableBindScreen(Screen parent) {
        super("Consumable Bind", parent);
    }

    @Override protected void initRuleControls(int inner, int half, int right) {
        Rule rule = ConsumableBind.rule(selected);
        button(fit(itemName(rule.item()), inner), 12, 79, inner, this::chooseNewItem);
        EditBox delay = new EditBox(font, left + 12, top + 123, half, 20, Component.literal(delayLabel()));
        delay.setMaxLength(4);
        delay.setFilter(value -> value.chars().allMatch(character -> character >= '0' && character <= '9'));
        delay.setValue(delayDraft == null ? Integer.toString(rule.delayMs()) : delayDraft);
        delay.setResponder(value -> delayDraft = value);
        addRenderableWidget(delay);
        button(text("Зберегти затримку", "Сохранить задержку", "Save delay"), right, 123, half, this::saveDelay);
    }

    private void saveDelay() {
        if (delayDraft == null) return;
        int millis;
        try {
            millis = Integer.parseInt(delayDraft);
        } catch (NumberFormatException ignored) {
            millis = -1;
        }
        if (millis < 0 || millis > ConsumableBind.MAX_DELAY_MS) {
            status = text("Діапазон затримки 0–2000 мс.", "Диапазон задержки 0–2000 мс.", "Enter a delay from 0 to 2000 ms.");
            return;
        }
        Rule rule = ConsumableBind.rule(selected);
        if (!ConsumableBind.update(selected, new Rule(rule.item(), millis))) {
            status = saveError();
            return;
        }
        delayDraft = null;
        status = text("Затримку збережено.", "Задержка сохранена.", "Delay saved.");
        rebuildWidgets();
    }

    @Override protected void chooseNewItem() {
        int index = selected;
        openPicker(item -> {
            Rule rule = ConsumableBind.rule(index);
            status = ConsumableBind.update(index, new Rule(item, rule.delayMs())) ? "" : saveError();
        });
    }

    private static String delayLabel() {
        return text("Затримка до використання, мс", "Задержка до использования, мс", "Delay before use, ms");
    }

    @Override protected void drawRuleLabels(GuiGraphics graphics) {
        graphics.drawString(font, delayLabel(), left + 12, top + 109, 0xFFD6CEE5, false);
    }

    @Override protected void ruleSelected() { delayDraft = null; }
    @Override protected KeyMapping key(int index) { return ConsumableBind.key(index); }
    @Override protected boolean configured(int index) { return !empty(index); }
    @Override protected boolean empty(int index) { return ConsumableBind.rule(index).item() == null; }
    @Override protected boolean deleteRule(int index) { return ConsumableBind.delete(minecraft, index); }
    @Override protected String addLabel() { return text("Додати предмет", "Добавить предмет", "Add item"); }
    @Override protected String deleteLabel() { return text("Видалити предмет", "Удалить предмет", "Delete item"); }
    @Override protected boolean isEnabled() { return ConsumableBind.isEnabled(); }
    @Override protected void toggle() { ConsumableBind.toggle(); }
    @Override protected String hint() {
        return text("Одне натискання · одне використання.", "Одно нажатие · одно использование.", "One press · one use.");
    }
}
