package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class AutoBuyScreen extends Screen {
    private static final int TEXT = 0xFFE8EDF8;
    private static final int MUTED = 0xFF9BAAC2;
    private static final int ERROR = 0xFFFF9292;
    private static final NumberFormat NUMBERS = NumberFormat.getIntegerInstance(Locale.forLanguageTag("uk-UA"));

    private final Screen parent;
    private final BooleanSupplier moduleEnabled;
    private final Runnable toggleModule;
    private final Supplier<String> moduleStatus;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int listWidth;
    private int contentX;
    private int contentWidth;
    private int page;
    private int rows;
    private String draftId;
    private String itemId;
    private String itemLabel;
    private String stackJson;
    private ItemStack preview = ItemStack.EMPTY;
    private String minPriceDraft = "0";
    private String maxPriceDraft = "";
    private String rangeLabel = text("Введи межі ціни", "Введи диапазон цен", "Enter a price range");
    private AutoBuySettings.PriceMode mode = AutoBuySettings.PriceMode.PER_ITEM;
    private AutoBuySettings.QuantityMode quantityMode = AutoBuySettings.QuantityMode.ANY;
    private boolean ruleEnabled = true;
    private boolean draftDirty;
    private String notice = "";
    private boolean noticeError;
    private EditBox minPriceField;
    private EditBox maxPriceField;
    private Button moduleButton;

    public AutoBuyScreen(Screen parent, BooleanSupplier moduleEnabled, Runnable toggleModule,
                         Supplier<String> moduleStatus) {
        super(Component.literal("AutoBuy"));
        this.parent = parent;
        this.moduleEnabled = moduleEnabled;
        this.toggleModule = toggleModule;
        this.moduleStatus = moduleStatus;
        if (!AutoBuySettings.rules().isEmpty()) loadDraft(AutoBuySettings.rules().getFirst());
    }

    @Override
    protected void init() {
        clearWidgets();
        panelWidth = Math.min(640, Math.max(280, width - 20));
        panelHeight = Math.min(330, Math.max(238, height - 16));
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        listWidth = Math.max(112, panelWidth / 3);
        contentX = left + listWidth + 16;
        contentWidth = panelWidth - listWidth - 28;
        rows = Math.max(3, (panelHeight - 144) / 24);

        List<AutoBuySettings.Rule> rules = AutoBuySettings.rules();
        page = Math.min(page, Math.max(0, (rules.size() - 1) / rows));
        for (int row = 0; row < rows; row++) {
            int index = page * rows + row;
            if (index >= rules.size()) break;
            AutoBuySettings.Rule rule = rules.get(index);
            String label = (rule.enabled() ? "✓ " : "○ ") + rule.label();
            addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(label, listWidth - 26)), button -> {
                loadDraft(rule);
                notice = "";
                init();
            }).bounds(left + 10, top + 53 + row * 24, listWidth - 14, 21).build());
        }
        addRenderableWidget(Button.builder(Component.literal("‹"), button -> {
            page--;
            init();
        }).bounds(left + 10, top + panelHeight - 80, 28, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal("›"), button -> {
            page++;
            init();
        }).bounds(left + listWidth - 32, top + panelHeight - 80, 28, 20).build()).active = (page + 1) * rows < rules.size();
        int halfList = (listWidth - 20) / 2;
        addRenderableWidget(Button.builder(Component.literal(text("Предмети", "Предметы", "Items")), button ->
                minecraft.setScreen(new ItemPicker(this, stack -> selectItem(stack, false))))
                .bounds(left + 10, top + panelHeight - 54, halfList, 20).build());
        addRenderableWidget(Button.builder(Component.literal(text("Із руки", "Из руки", "Use held")), button -> {
            if (minecraft.player == null || minecraft.player.getMainHandItem().isEmpty()) {
                showNotice(text("Візьми потрібний предмет у головну руку.", "Возьми нужный предмет в основную руку.",
                        "Hold the item in your main hand."), true);
            } else {
                selectItem(minecraft.player.getMainHandItem().copy(), true);
                init();
            }
        }).bounds(left + 14 + halfList, top + panelHeight - 54, halfList, 20).build());

        int gap = 8;
        int fieldWidth = (contentWidth - gap) / 2;
        minPriceField = new EditBox(font, contentX, top + 99, fieldWidth, 20,
                Component.literal(text("Мін. ціна", "Мин. цена", "Min. price")));
        minPriceField.setMaxLength(19);
        minPriceField.setValue(minPriceDraft);
        minPriceField.setHint(Component.literal("0"));
        minPriceField.setResponder(value -> {
            minPriceDraft = value;
            draftDirty = true;
            updateRangeLabel();
        });
        minPriceField.active = itemId != null;
        addRenderableWidget(minPriceField);
        maxPriceField = new EditBox(font, contentX + fieldWidth + gap, top + 99, fieldWidth, 20,
                Component.literal(text("Макс. ціна", "Макс. цена", "Max. price")));
        maxPriceField.setMaxLength(19);
        maxPriceField.setValue(maxPriceDraft);
        maxPriceField.setHint(Component.literal("200000"));
        maxPriceField.setResponder(value -> {
            maxPriceDraft = value;
            draftDirty = true;
            updateRangeLabel();
        });
        maxPriceField.active = itemId != null;
        addRenderableWidget(maxPriceField);
        addRenderableWidget(Button.builder(modeLabel(), button -> {
            mode = mode == AutoBuySettings.PriceMode.PER_ITEM ? AutoBuySettings.PriceMode.PER_LOT
                    : AutoBuySettings.PriceMode.PER_ITEM;
            draftDirty = true;
            button.setMessage(modeLabel());
        }).bounds(contentX, top + 143, fieldWidth, 18).build()).active = itemId != null;
        addRenderableWidget(Button.builder(ruleLabel(), button -> {
            ruleEnabled = !ruleEnabled;
            draftDirty = true;
            button.setMessage(ruleLabel());
        }).bounds(contentX + fieldWidth + gap, top + 143, fieldWidth, 18).build()).active = itemId != null;
        addRenderableWidget(Button.builder(quantityLabel(), button -> {
            quantityMode = switch (quantityMode) {
                case ANY -> AutoBuySettings.QuantityMode.SINGLE;
                case SINGLE -> AutoBuySettings.QuantityMode.FULL_STACK;
                case FULL_STACK -> AutoBuySettings.QuantityMode.ANY;
            };
            draftDirty = true;
            button.setMessage(quantityLabel());
        }).bounds(contentX, top + 163, contentWidth, 18).build()).active = itemId != null;

        addRenderableWidget(Button.builder(Component.literal(text("Зберегти", "Сохранить", "Save")), button -> saveDraft())
                .bounds(contentX, top + panelHeight - 54, fieldWidth, 20).build()).active = itemId != null;
        addRenderableWidget(Button.builder(Component.literal(text("Видалити", "Удалить", "Delete")), button -> deleteDraft())
                .bounds(contentX + fieldWidth + gap, top + panelHeight - 54, fieldWidth, 20).build())
                .active = draftId != null && rules.stream().anyMatch(rule -> rule.id().equals(draftId));
        addRenderableWidget(Button.builder(Component.literal(text("Назад", "Назад", "Back")), button -> onClose())
                .bounds(left + 10, top + panelHeight - 27, 88, 20).build());
        moduleButton = addRenderableWidget(Button.builder(moduleLabel(), button -> {
            if (!moduleEnabled.getAsBoolean() && draftDirty && !saveDraft()) return;
            if (!moduleEnabled.getAsBoolean() && AutoBuySettings.rules().stream().noneMatch(AutoBuySettings.Rule::enabled)) {
                showNotice(text("Збережи й увімкни хоча б одне правило.", "Сохрани и включи хотя бы одно правило.",
                        "Save and enable at least one rule."), true);
                return;
            }
            toggleModule.run();
            if (moduleEnabled.getAsBoolean()) minecraft.setScreen(null);
        }).bounds(left + panelWidth - 122, top + panelHeight - 27, 110, 20).build());
        if (itemId != null) setInitialFocus(minPriceField);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xB8070B14);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fillGradient(left, top, left + panelWidth, top + panelHeight,
                HudSettings.shade(0.03F), HudSettings.shade(0.13F));
        graphics.fill(left, top, left + panelWidth, top + 2, HudSettings.accent());
        graphics.drawString(font, title, left + 12, top + 12, TEXT, false);
        String status = notice.isEmpty() ? moduleStatus.get() : notice;
        graphics.drawString(font, font.plainSubstrByWidth(status == null ? "" : status, panelWidth - 24),
                left + 12, top + 29, noticeError && !notice.isEmpty() ? ERROR : MUTED, false);
        graphics.fill(left + listWidth + 5, top + 47, left + listWidth + 6, top + panelHeight - 34, 0x405D708F);
        graphics.drawString(font, text("Правила", "Правила", "Rules"), left + 12, top + 43, MUTED, false);
        if (AutoBuySettings.rules().isEmpty()) {
            graphics.drawString(font, text("Додай предмет", "Добавь предмет", "Add an item"), left + 14, top + 61, MUTED, false);
        }
        List<AutoBuySettings.Rule> rules = AutoBuySettings.rules();
        for (int row = 0; row < rows; row++) {
            int index = page * rows + row;
            if (index < rules.size() && rules.get(index).id().equals(draftId)) {
                graphics.fill(left + 6, top + 55 + row * 24, left + 8, top + 72 + row * 24, HudSettings.accent());
            }
        }
        graphics.drawCenteredString(font, (page + 1) + " / " + Math.max(1, (rules.size() + rows - 1) / rows),
                left + listWidth / 2, top + panelHeight - 74, MUTED);
        if (itemId == null) {
            String prompt = text("Додай предмет зі списку або з руки.", "Добавь предмет из списка или из руки.",
                    "Choose an item from the list or use the held item.");
            graphics.drawString(font, font.plainSubstrByWidth(prompt, contentWidth), contentX, top + 56, MUTED, false);
        } else {
            if (!preview.isEmpty()) graphics.renderItem(preview, contentX, top + 51);
            graphics.drawString(font, font.plainSubstrByWidth(itemLabel, contentWidth - 24), contentX + 23, top + 54, TEXT, false);
            String detail = stackJson == null ? text("Тип предмета", "Тип предмета", "Item type")
                    : text("Точні властивості", "Точные свойства", "Exact properties");
            graphics.drawString(font, font.plainSubstrByWidth(detail, contentWidth), contentX, top + 75, MUTED, false);
        }
        graphics.drawString(font, text("Мін. ціна", "Мин. цена", "Min. price"), contentX, top + 87, MUTED, false);
        graphics.drawString(font, text("Макс. ціна", "Макс. цена", "Max. price"), maxPriceField.getX(), top + 87, MUTED, false);
        graphics.drawString(font, font.plainSubstrByWidth(rangeLabel, contentWidth),
                contentX, top + 129, HudSettings.accent(), false);
        moduleButton.setMessage(moduleLabel());
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void loadDraft(AutoBuySettings.Rule rule) {
        draftId = rule.id();
        itemId = rule.itemId();
        itemLabel = rule.label();
        stackJson = rule.stackJson();
        minPriceDraft = Long.toString(rule.minPrice());
        maxPriceDraft = Long.toString(rule.maxPrice());
        mode = rule.priceMode();
        quantityMode = rule.quantityMode();
        ruleEnabled = rule.enabled();
        draftDirty = false;
        preview = preview(rule);
        updateRangeLabel();
    }

    private void selectItem(ItemStack stack, boolean exact) {
        try {
            AutoBuySettings.Rule draft = AutoBuySettings.createRangeRule(stack, 0, 1, AutoBuySettings.PriceMode.PER_ITEM,
                    AutoBuySettings.QuantityMode.ANY, exact);
            loadDraft(draft);
            maxPriceDraft = "";
            updateRangeLabel();
            draftDirty = true;
            notice = text("Введи мінімальну й максимальну ціну та збережи правило.",
                    "Введи минимальную и максимальную цену и сохрани правило.", "Enter the minimum and maximum prices and save the rule.");
            noticeError = false;
        } catch (RuntimeException exception) {
            showNotice(text("Не вдалося додати предмет. Спробуй у відкритому світі.",
                    "Не удалось добавить предмет. Попробуй в открытом мире.", "Could not add the item. Try again in a world."), true);
        }
    }

    private boolean saveDraft() {
        try {
            AutoBuySettings.Rule rule = new AutoBuySettings.Rule(draftId, itemId, itemLabel, stackJson,
                    Long.parseLong(minPriceDraft.strip()), Long.parseLong(maxPriceDraft.strip()),
                    mode, quantityMode, ruleEnabled);
            AutoBuySettings.put(rule);
            draftDirty = false;
            showNotice(text("Правило збережено. Від ", "Правило сохранено. От ", "Rule saved. From ") + NUMBERS.format(rule.minPrice())
                    + text(" до ", " до ", " to ") + NUMBERS.format(rule.maxPrice()), false);
            init();
            return true;
        } catch (NumberFormatException exception) {
            showNotice(text("Введи мінімальну й максимальну ціну цілими числами.",
                    "Введи минимальную и максимальную цену целыми числами.", "Enter whole numbers for both prices."), true);
        } catch (IllegalArgumentException exception) {
            showNotice(exception.getMessage(), true);
        } catch (IOException exception) {
            showNotice(text("Не вдалося зберегти AutoBuy на диск.", "Не удалось сохранить AutoBuy на диск.",
                    "Could not save AutoBuy settings."), true);
        }
        return false;
    }

    private void deleteDraft() {
        try {
            AutoBuySettings.remove(draftId);
            draftId = null;
            itemId = null;
            preview = ItemStack.EMPTY;
            minPriceDraft = "0";
            maxPriceDraft = "";
            quantityMode = AutoBuySettings.QuantityMode.ANY;
            updateRangeLabel();
            draftDirty = false;
            if (!AutoBuySettings.rules().isEmpty()) loadDraft(AutoBuySettings.rules().getFirst());
            showNotice(text("Правило видалено.", "Правило удалено.", "Rule deleted."), false);
            init();
        } catch (IOException exception) {
            showNotice(text("Не вдалося зберегти зміни на диск.", "Не удалось сохранить изменения на диск.",
                    "Could not save your changes."), true);
        }
    }

    private ItemStack preview(AutoBuySettings.Rule rule) {
        if (rule.stackJson() != null && Minecraft.getInstance().level != null) {
            try {
                return ItemStack.CODEC.parse(Minecraft.getInstance().level.registryAccess()
                        .createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(rule.stackJson())).getOrThrow();
            } catch (RuntimeException ignored) {
                // A removed custom component still leaves the base item available in the editor.
            }
        }
        Identifier id = Identifier.tryParse(rule.itemId());
        return id == null || !BuiltInRegistries.ITEM.containsKey(id) ? ItemStack.EMPTY
                : BuiltInRegistries.ITEM.getValue(id).getDefaultInstance();
    }

    private void showNotice(String value, boolean error) {
        notice = value;
        noticeError = error;
    }

    private void updateRangeLabel() {
        rangeLabel = text("Введи межі ціни", "Введи диапазон цен", "Enter a price range");
        if (minPriceDraft.isBlank() || maxPriceDraft.isBlank()) return;
        try {
            long minPrice = Long.parseLong(minPriceDraft.strip());
            long maxPrice = Long.parseLong(maxPriceDraft.strip());
            if (minPrice >= 0 && maxPrice > 0 && minPrice <= maxPrice) {
                rangeLabel = text("Від ", "От ", "From ") + NUMBERS.format(minPrice)
                        + text(" до ", " до ", " to ") + NUMBERS.format(maxPrice) + text(" включно", " включительно", " inclusive");
            }
        } catch (NumberFormatException ignored) {
            // Incomplete numeric input is validated when the rule is saved.
        }
    }

    private Component modeLabel() {
        return Component.literal(mode == AutoBuySettings.PriceMode.PER_ITEM
                ? text("За 1 шт.", "За 1 шт.", "Per item")
                : text("За лот", "За лот", "Per lot"));
    }

    private Component quantityLabel() {
        String label = switch (quantityMode) {
            case ANY -> text("будь-яка", "любое", "any");
            case SINGLE -> text("1 шт.", "1 шт.", "1 item");
            case FULL_STACK -> text("повний стак", "полный стак", "full stack");
        };
        return Component.literal(text("Кількість · ", "Кол-во · ", "Quantity · ") + label);
    }

    private Component ruleLabel() {
        return Component.literal(ruleEnabled ? text("Активне ✓", "Активно ✓", "Active ✓") : text("Активне ○", "Активно ○", "Active ○"));
    }

    private Component moduleLabel() {
        return Component.literal(moduleEnabled.getAsBoolean() ? text("Зупинити", "Остановить", "Stop")
                : text("Увімкнути", "Включить", "Enable"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private static final class ItemPicker extends Screen {
        private final AutoBuyScreen parent;
        private final Consumer<ItemStack> selected;
        private final List<ItemStack> items;
        private final List<Button> itemButtons = new ArrayList<>();
        private List<ItemStack> matches = List.of();
        private String searchDraft = "";
        private int page;
        private int left;
        private int top;
        private int panelWidth;
        private int panelHeight;
        private int rows;
        private Button previous;
        private Button next;

        private ItemPicker(AutoBuyScreen parent, Consumer<ItemStack> selected) {
            super(Component.literal(text("Вибір предмета", "Выбор предмета", "Choose an item")));
            this.parent = parent;
            this.selected = selected;
            items = BuiltInRegistries.ITEM.stream().map(item -> item.getDefaultInstance())
                    .filter(stack -> !stack.isEmpty())
                    .sorted(Comparator.comparing(stack -> stack.getHoverName().getString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }

        @Override
        protected void init() {
            clearWidgets();
            itemButtons.clear();
            panelWidth = Math.min(500, Math.max(280, width - 20));
            panelHeight = Math.min(340, Math.max(200, height - 16));
            left = (width - panelWidth) / 2;
            top = (height - panelHeight) / 2;
            rows = Math.max(3, (panelHeight - 110) / 24);
            EditBox search = new EditBox(font, left + 12, top + 38, panelWidth - 24, 20,
                    Component.literal(text("Пошук предмета", "Поиск предмета", "Search items")));
            search.setMaxLength(64);
            search.setValue(searchDraft);
            search.setHint(Component.literal(text("Назва або minecraft:diamond", "Название или minecraft:diamond",
                    "Name or minecraft:diamond")));
            search.setResponder(value -> {
                searchDraft = value;
                page = 0;
                updateMatches();
            });
            addRenderableWidget(search);
            previous = addRenderableWidget(Button.builder(Component.literal("‹"), button -> {
                page--;
                updateButtons();
            }).bounds(left + 12, top + panelHeight - 29, 34, 20).build());
            next = addRenderableWidget(Button.builder(Component.literal("›"), button -> {
                page++;
                updateButtons();
            }).bounds(left + 52, top + panelHeight - 29, 34, 20).build());
            addRenderableWidget(Button.builder(Component.literal(text("Назад", "Назад", "Back")), button -> onClose())
                    .bounds(left + panelWidth - 100, top + panelHeight - 29, 88, 20).build());
            updateMatches();
            setInitialFocus(search);
        }

        private void updateMatches() {
            String search = searchDraft.strip().toLowerCase(Locale.ROOT);
            matches = items.stream().filter(stack -> stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(search)
                    || BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains(search)).toList();
            updateButtons();
        }

        private void updateButtons() {
            for (Button button : itemButtons) removeWidget(button);
            itemButtons.clear();
            page = Math.min(page, Math.max(0, (matches.size() - 1) / rows));
            for (int row = 0; row < rows; row++) {
                int index = page * rows + row;
                if (index >= matches.size()) break;
                ItemStack stack = matches.get(index);
                String label = font.plainSubstrByWidth(stack.getHoverName().getString(), panelWidth - 72);
                itemButtons.add(addRenderableWidget(Button.builder(Component.literal(label), button -> {
                    selected.accept(stack.copy());
                    minecraft.setScreen(parent);
                }).bounds(left + 12, top + 70 + row * 24, panelWidth - 24, 21).build()));
            }
            previous.active = page > 0;
            next.active = (page + 1) * rows < matches.size();
        }

        @Override
        public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, 0xC0070B14);
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            renderBackground(graphics, mouseX, mouseY, partialTick);
            graphics.fillGradient(left, top, left + panelWidth, top + panelHeight,
                    HudSettings.shade(0.03F), HudSettings.shade(0.13F));
            graphics.fill(left, top, left + panelWidth, top + 2, HudSettings.accent());
            graphics.drawString(font, title, left + 12, top + 15, TEXT, false);
            graphics.drawCenteredString(font, (page + 1) + " / " + Math.max(1, (matches.size() + rows - 1) / rows),
                    left + panelWidth / 2, top + panelHeight - 23, MUTED);
            super.render(graphics, mouseX, mouseY, partialTick);
            for (int row = 0; row < itemButtons.size(); row++) {
                ItemStack stack = matches.get(page * rows + row);
                graphics.renderItem(stack, left + 17, top + 72 + row * 24);
            }
        }

        @Override
        public void onClose() {
            minecraft.setScreen(parent);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
