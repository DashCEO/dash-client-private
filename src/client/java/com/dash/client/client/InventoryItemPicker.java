package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import com.dash.client.client.InventoryBindSupport.ItemChoice;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

final class InventoryItemPicker extends Screen {
    private static final int PANEL_WIDTH = 232;
    private static final int PANEL_HEIGHT = 165;
    private static final int CELL = 22;
    private static final int FIRST_COLUMN = 17;
    private static final int FIRST_ROW = 31;
    private final Screen parent;
    private final Consumer<ItemChoice> select;
    private int left;
    private int top;

    InventoryItemPicker(Screen parent, Consumer<ItemChoice> select) {
        super(Component.literal(text("Предмети інвентаря", "Предметы инвентаря", "Inventory items")));
        this.parent = parent;
        this.select = select;
    }

    @Override protected void init() {
        left = (width - PANEL_WIDTH) / 2;
        top = (height - PANEL_HEIGHT) / 2;
        addRenderableWidget(Button.builder(Component.literal(text("Назад", "Назад", "Back")), button -> onClose())
                .bounds(left + 145, top + PANEL_HEIGHT - 33, 68, 20).build());
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x99080B14);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fillGradient(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, HudSettings.shade(.03F), HudSettings.shade(.13F));
        graphics.fill(left, top, left + PANEL_WIDTH, top + 2, HudSettings.accent());
        graphics.drawString(font, title, left + FIRST_COLUMN, top + 13, 0xFFF2EEFF, false);
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            int index = index(slot);
            drawCell(graphics, stack(slot), gridX(index), gridY(index), mouseX, mouseY);
        }
        int offhandX = left + FIRST_COLUMN;
        int offhandY = top + PANEL_HEIGHT - 34;
        drawCell(graphics, stack(Inventory.SLOT_OFFHAND), offhandX, offhandY, mouseX, mouseY);
        graphics.drawString(font, text("Друга рука", "Вторая рука", "Offhand"),
                offhandX + CELL + 4, offhandY + 6, 0xFFD6CEE5, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawCell(GuiGraphics graphics, ItemStack stack, int x, int y, int mouseX, int mouseY) {
        boolean hovered = inCell(mouseX, mouseY, x, y) && !stack.isEmpty();
        graphics.fill(x, y, x + CELL - 2, y + CELL - 2, HudSettings.shade(hovered ? .43F : .29F));
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, x + 2, y + 2);
            graphics.renderItemDecorations(font, stack, x + 2, y + 2);
            if (hovered) graphics.setTooltipForNextFrame(font, stack.getHoverName(), mouseX, mouseY);
        }
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                int index = index(slot);
                if (inCell(event.x(), event.y(), gridX(index), gridY(index))) {
                    return choose(slot);
                }
            }
            if (inCell(event.x(), event.y(), left + FIRST_COLUMN, top + PANEL_HEIGHT - 34)) {
                return choose(Inventory.SLOT_OFFHAND);
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean choose(int slot) {
        ItemChoice item = ItemChoice.of(stack(slot));
        if (item == null) return false;
        select.accept(item);
        onClose();
        return true;
    }

    private ItemStack stack(int slot) {
        return minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getInventory().getItem(slot);
    }

    private static int index(int slot) {
        return Inventory.isHotbarSlot(slot) ? slot + 27 : slot - 9;
    }

    private int gridX(int index) {
        return left + FIRST_COLUMN + index % 9 * CELL;
    }

    private int gridY(int index) {
        return top + FIRST_ROW + index / 9 * CELL;
    }

    private static boolean inCell(double mouseX, double mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + CELL - 2 && mouseY >= y && mouseY < y + CELL - 2;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
