package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import com.dash.client.client.SwapBind.Hand;
import com.dash.client.client.SwapBind.Rule;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;

final class SwapBindScreen extends InventoryBindSettingsScreen {
    private int displayedRevision;

    SwapBindScreen(Screen parent) {
        super("Swap Bind", parent);
    }

    @Override protected void initRuleControls(int inner, int half, int right) {
        displayedRevision = SwapBind.revision();
        Rule rule = SwapBind.rule(selected);
        button(fit("A · " + itemName(rule.first()), inner), 12, 79, inner, () -> pick(true));
        button(fit("B · " + itemName(rule.second()), inner), 12, 103, inner, () -> pick(false));
        button(handLabel(Hand.LEFT, rule.hand()), 12, 128, half, () -> target(Hand.LEFT));
        button(handLabel(Hand.RIGHT, rule.hand()), right, 128, half, () -> target(Hand.RIGHT));
    }

    @Override public void tick() {
        if (displayedRevision != SwapBind.revision()) rebuildWidgets();
    }

    private String handLabel(Hand candidate, Hand selectedHand) {
        String label = candidate == Hand.LEFT ? text("Ліва рука", "Левая рука", "Left hand")
                : text("Права рука", "Правая рука", "Right hand");
        return candidate == selectedHand ? "✓ " + label : label;
    }

    private void pick(boolean first) {
        int index = selected;
        openPicker(item -> {
            Rule old = SwapBind.rule(index);
            if (item.equals(first ? old.second() : old.first())) {
                status = text("Обери два різні предмети.", "Выбери два разных предмета.", "Choose two different items.");
                return;
            }
            Rule updated = first ? new Rule(item, old.second(), old.hand()) : new Rule(old.first(), item, old.hand());
            status = SwapBind.update(index, updated) ? "" : saveError();
        });
    }

    private void target(Hand hand) {
        Rule old = SwapBind.rule(selected);
        status = SwapBind.update(selected, new Rule(old.first(), old.second(), hand, old.autoTotems())) ? "" : saveError();
        rebuildWidgets();
    }

    @Override protected KeyMapping key(int index) { return SwapBind.key(index); }
    @Override protected boolean configured(int index) { return SwapBind.rule(index).configured(); }
    @Override protected boolean empty(int index) { return SwapBind.rule(index).empty(); }
    @Override protected boolean deleteRule(int index) { return SwapBind.delete(minecraft, index); }
    @Override protected String addLabel() { return text("Додати пару", "Добавить пару", "Add pair"); }
    @Override protected String deleteLabel() { return text("Видалити пару", "Удалить пару", "Delete pair"); }
    @Override protected boolean isEnabled() { return SwapBind.isEnabled(); }
    @Override protected void toggle() { SwapBind.toggle(); }
    @Override protected void chooseNewItem() { pick(true); }
    @Override protected String hint() {
        if (SwapBind.rule(selected).autoTotems()) {
            return key(selected).isUnbound()
                    ? text("Автопара тотемів. Признач клавішу.", "Автопара тотемов. Назначь клавишу.", "Automatic totem pair. Set a key.")
                    : text("Автопара тотемів з інвентарю.", "Автопара тотемов из инвентаря.", "Automatic totem pair from inventory.");
        }
        return text("Свап шукає предмети в хотбарі й інвентарі.", "Свап ищет предметы в хотбаре и инвентаре.",
                "Swap searches the hotbar and inventory.");
    }
}
