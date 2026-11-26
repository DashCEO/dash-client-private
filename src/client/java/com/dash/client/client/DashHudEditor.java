package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

final class DashHudEditor extends Screen {
    private static final int WHITE = 0xFFF5FAFC;
    private static final int MUTED = 0xFFAFBBC7;
    private static final DashHudLayout.Widget[] WIDGETS = DashHudLayout.Widget.values();
    private static final int[] COLORS = {0, 0xFF63D7F7, 0xFFBFAFFF, 0xFFFF938B, 0xFF91EAA8, 0xFFF1F7FA};

    private enum Drag { NONE, MOVE, RESIZE, SIZE, OPACITY }

    private final Screen parent;
    private DashHudLayout.Widget selected;
    private int listOffset;
    private Drag dragging = Drag.NONE;
    private double grabX;
    private double grabY;
    private float initialSize;
    private float initialWidth;

    private int left;
    private int top;
    private int right;
    private int bottom;
    private int inspector;
    private int listTop;
    private int controlsTop;
    private int take;
    private float previewX;
    private float previewY;
    private float previewScale;
    private float panelScale;

    DashHudEditor(Screen parent) {
        this(parent, DashHudLayout.Widget.WATERMARK);
    }

    DashHudEditor(Screen parent, DashHudLayout.Widget selected) {
        super(Component.literal("Dash HUD"));
        this.parent = parent;
        this.selected = selected;
        listOffset = selected.ordinal();
    }

    @Override
    protected void init() {
        panelScale = Math.min(1, Math.min(Math.max(1, width) / 164.0F, Math.max(1, height) / 176.0F));
        int layoutWidth = (int) (width / panelScale);
        int layoutHeight = (int) (height / panelScale);
        left = Math.max(6, (layoutWidth - 590) / 2);
        top = Math.max(6, (layoutHeight - 364) / 2);
        right = layoutWidth - left;
        bottom = layoutHeight - top;
        inspector = right - Math.min(170, (right - left) * 42 / 100);
        listTop = top + 46;
        controlsTop = Math.max(listTop + 14, bottom - 85);
        take = Math.max(1, (controlsTop - listTop - 2) / 18);
        listOffset = Mth.clamp(listOffset, 0, Math.max(0, WIDGETS.length - take));
        int canvasWidth = Math.max(1, inspector - left - 22);
        int canvasHeight = Math.max(1, bottom - top - 58);
        previewScale = Math.min(canvasWidth / (float) Math.max(1, width), canvasHeight / (float) Math.max(1, height));
        previewX = left + 10 + (canvasWidth - previewScale * width) * .5F;
        previewY = top + 42 + (canvasHeight - previewScale * height) * .5F;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0x8504070B);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        var matrix = g.pose();
        matrix.pushMatrix();
        try {
            matrix.scale(panelScale);
            drawPanel(g, mouseX / panelScale, mouseY / panelScale);
        } finally {
            matrix.popMatrix();
        }
    }

    private void drawPanel(GuiGraphics g, float mouseX, float mouseY) {
        var face = DashTypography.font(font);
        int accent = DashAppearance.accent();
        DashShape.frame(g, left, top, right, bottom, DashAppearance.cornerRadius(), 0xFF3A4652, 0xFF10171E);
        DashShape.fill(g, left + 1, top + 1, right - 1, top + 31, 7, 0xFF1B2630);
        DashShape.fill(g, left + 8, top + 8, left + 11, top + 23, 1, accent);
        g.drawString(face, text("Редактор HUD", "Редактор HUD", "HUD Layout"), left + 16, top + 8, WHITE, false);
        g.drawString(face, DashHud.fit(face, text("Перетягни віджет або його кут", "Перетащи виджет или его угол",
                "Drag a widget or its corner"), right - left - 81), left + 16, top + 21, MUTED, false);
        button(g, right - 55, top + 7, right - 8, top + 25,
                text("Готово", "Готово", "Done"), hit(mouseX, mouseY, right - 55, top + 7, right - 8, top + 25), true);

        int x = Math.round(previewX);
        int y = Math.round(previewY);
        int farX = Math.round(previewX + width * previewScale);
        int farY = Math.round(previewY + height * previewScale);
        DashShape.frame(g, x, y, farX, farY, 6, 0xFF34434F, 0xFF10171D);
        for (int lineX = x + 16; lineX < farX - 2; lineX += 16) g.fill(lineX, y + 2, lineX + 1, farY - 2, 0x175B8190);
        for (int lineY = y + 16; lineY < farY - 2; lineY += 16) g.fill(x + 2, lineY, farX - 2, lineY + 1, 0x175B8190);
        g.enableScissor(x, y, farX, farY);
        for (var widget : WIDGETS) {
            if (widget != selected) drawWidget(g, widget);
        }
        drawWidget(g, selected);
        g.disableScissor();

        int panelX = inspector + 3;
        int panelRight = right - 8;
        DashShape.frame(g, inspector, top + 36, right - 5, bottom - 5, 7, 0xFF303B46, 0xFF161F28);
        g.enableScissor(panelX, listTop, panelRight, controlsTop);
        for (int i = 0; i < take && i + listOffset < WIDGETS.length; i++) {
            var widget = WIDGETS[i + listOffset];
            int rowY = listTop + 18 * i;
            boolean hover = hit(mouseX, mouseY, panelX + 3, rowY, panelRight - 3, rowY + 16);
            DashShape.fill(g, panelX + 3, rowY, panelRight - 3, rowY + 16, 4,
                    widget == selected ? 0xFF253A47 : hover ? 0xFF212E39 : 0xFF1A252E);
            int dot = DashHudLayout.enabled(widget) ? accent : 0xFF6B7680;
            DashShape.fill(g, panelX + 8, rowY + 6, panelX + 12, rowY + 10, 1, dot);
            g.drawString(face, DashHud.fit(face, widget.label(), panelRight - panelX - 25),
                    panelX + 17, rowY + 4, widget == selected ? WHITE : MUTED, false);
        }
        g.disableScissor();

        g.fill(panelX + 4, controlsTop, panelRight - 4, controlsTop + 1, 0xFF34434F);
        slider(g, controlsTop + 6, text("Розмір", "Размер", "Size"), DashHudLayout.size(selected),
                DashHudLayout.MIN_SIZE, DashHudLayout.MAX_SIZE);
        slider(g, controlsTop + 27, text("Прозорість", "Прозрачность", "Opacity"), DashHudLayout.opacity(selected),
                DashHudLayout.MIN_OPACITY, 1);
        int dotsY = controlsTop + 49;
        int step = Math.max(1, (panelRight - panelX - 8) / COLORS.length);
        for (int i = 0; i < COLORS.length; i++) {
            int slotX = panelX + 5 + i * step;
            int fill = COLORS[i] == 0 ? accent : COLORS[i];
            DashShape.frame(g, slotX, dotsY, slotX + step - 2, dotsY + 12, 3,
                    DashHudLayout.accent(selected) == COLORS[i] ? WHITE : 0xFF33424E, fill);
        }
        int buttonTop = controlsTop + 65;
        int half = (panelX + panelRight) / 2;
        boolean enabled = DashHudLayout.enabled(selected);
        button(g, panelX + 3, buttonTop, half - 1, buttonTop + 15, enabled
                ? text("Показано", "Показано", "Visible") : text("Приховано", "Скрыто", "Hidden"),
                hit(mouseX, mouseY, panelX + 3, buttonTop, half - 1, buttonTop + 15), enabled);
        button(g, half + 1, buttonTop, panelRight - 3, buttonTop + 15, text("Скинути", "Сбросить", "Reset"),
                hit(mouseX, mouseY, half + 1, buttonTop, panelRight - 3, buttonTop + 15), false);
    }

    private void drawWidget(GuiGraphics g, DashHudLayout.Widget widget) {
        float x = widgetX(widget);
        float y = widgetY(widget);
        int left = Math.round(x);
        int top = Math.round(y);
        int right = Math.round(x + widgetWidth(widget));
        int bottom = Math.round(y + widgetHeight(widget));
        int accent = DashHudLayout.accent(widget);
        if (accent == 0) accent = DashAppearance.accent();
        boolean enabled = DashHudLayout.enabled(widget);
        int background = enabled ? 0xE6202D37 : 0x9E192129;
        int panelAlpha = Math.round((background >>> 24) * DashHudLayout.opacity(widget));
        int edge = widget == selected ? accent : 0xFF52606B;
        DashShape.frame(g, left, top, right, bottom, 4, edge, panelAlpha << 24 | (background & 0xFFFFFF));
        var face = DashTypography.font(font);
        if (bottom - top >= 10) {
            g.drawString(face, DashHud.fit(face, widget.label(), right - left - 7),
                    left + 3, top + Math.max(1, (bottom - top - 8) / 2), enabled ? WHITE : MUTED, false);
        }
        if (widget == selected) DashShape.fill(g, right - 5, bottom - 5, right, bottom, 1, accent);
    }

    private void slider(GuiGraphics g, int y, String title, float value, float min, float max) {
        int x = inspector + 10;
        int right = this.right - 12;
        var face = DashTypography.font(font);
        String amount = Math.round(100 * value) + "%";
        g.drawString(face, DashHud.fit(face, title, right - x - face.width(amount) - 4), x, y, MUTED, false);
        g.drawString(face, amount, right - face.width(amount), y, WHITE, false);
        int at = x + Math.round((right - x) * (value - min) / (max - min));
        DashShape.fill(g, x, y + 12, right, y + 16, 2, 0xFF30414F);
        DashShape.fill(g, x, y + 12, at, y + 16, 2, DashAppearance.accent());
        DashShape.fill(g, at - 3, y + 10, at + 3, y + 18, 3, WHITE);
    }

    private void button(GuiGraphics g, int x, int y, int right, int bottom, String label, boolean hover, boolean accent) {
        int color = accent ? hover ? 0xFF315769 : 0xFF263F4F : hover ? 0xFF2F3C48 : 0xFF24303A;
        DashShape.fill(g, x, y, right, bottom, 4, color);
        var face = DashTypography.font(font);
        String fitted = DashHud.fit(face, label, right - x - 4);
        g.drawString(face, fitted, (x + right - face.width(fitted)) / 2, y + (bottom - y - 8) / 2,
                accent ? WHITE : MUTED, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);
        double x = event.x() / panelScale;
        double y = event.y() / panelScale;
        if (hit(x, y, right - 55, top + 7, right - 8, top + 25)) {
            onClose();
            return true;
        }
        if (x >= inspector && x < right - 5) {
            if (y >= listTop && y < controlsTop) {
                int index = (int) (y - listTop) / 18 + listOffset;
                if (index < WIDGETS.length) selected = WIDGETS[index];
                return true;
            }
            if (y >= controlsTop + 9 && y < controlsTop + 26) {
                dragging = Drag.SIZE;
                updateSlider(x, Drag.SIZE);
                return true;
            }
            if (y >= controlsTop + 30 && y < controlsTop + 47) {
                dragging = Drag.OPACITY;
                updateSlider(x, Drag.OPACITY);
                return true;
            }
            if (y >= controlsTop + 49 && y < controlsTop + 61) {
                int step = Math.max(1, (right - inspector - 19) / COLORS.length);
                int color = Mth.clamp((int) (x - inspector - 8) / step, 0, COLORS.length - 1);
                DashHudLayout.accent(selected, COLORS[color]);
                DashHudLayout.save();
                return true;
            }
            if (y >= controlsTop + 65 && y < controlsTop + 80) {
                if (x < (inspector + right - 5) / 2) DashHudLayout.toggle(selected);
                else DashHudLayout.reset(selected);
                return true;
            }
        }
        if (hit(x, y, previewX, previewY, previewX + previewScale * width, previewY + previewScale * height)) {
            var widget = widgetAt(x, y);
            if (widget != null) {
                selected = widget;
                int index = widget.ordinal();
                if (index < listOffset || index >= listOffset + take) {
                    listOffset = Mth.clamp(index, 0, Math.max(0, WIDGETS.length - take));
                }
                float wx = widgetX(widget);
                float wy = widgetY(widget);
                float ww = widgetWidth(widget);
                float wh = widgetHeight(widget);
                if (x >= wx + ww - Math.min(7, ww * .35F) && y >= wy + wh - Math.min(7, wh * .55F)) {
                    dragging = Drag.RESIZE;
                    grabX = x;
                    initialSize = DashHudLayout.size(widget);
                    initialWidth = Math.max(1, ww);
                } else {
                    dragging = Drag.MOVE;
                    grabX = x - wx;
                    grabY = y - wy;
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (dragging == Drag.NONE) return super.mouseDragged(event, deltaX, deltaY);
        double x = event.x() / panelScale;
        double y = event.y() / panelScale;
        switch (dragging) {
            case MOVE -> DashHudLayout.move(selected, (float) ((x - grabX - previewX) / previewScale),
                    (float) ((y - grabY - previewY) / previewScale), width, height);
            case RESIZE -> DashHudLayout.size(selected, (float) (initialSize * (1 + (x - grabX) / initialWidth)));
            case SIZE, OPACITY -> updateSlider(x, dragging);
            default -> {}
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging == Drag.NONE) return super.mouseReleased(event);
        dragging = Drag.NONE;
        DashHudLayout.save();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (hit(mouseX / panelScale, mouseY / panelScale, inspector, listTop, right, controlsTop) && vertical != 0) {
            listOffset = Mth.clamp(listOffset + (vertical > 0 ? -1 : 1), 0, Math.max(0, WIDGETS.length - take));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public void onClose() {
        DashHudLayout.save();
        minecraft.setScreen(parent);
    }

    private void updateSlider(double x, Drag slider) {
        float portion = Mth.clamp((float) ((x - inspector - 10) / Math.max(1, right - inspector - 22)), 0, 1);
        if (slider == Drag.SIZE) {
            DashHudLayout.size(selected, Mth.lerp(portion, DashHudLayout.MIN_SIZE, DashHudLayout.MAX_SIZE));
        } else {
            DashHudLayout.opacity(selected, Mth.lerp(portion, DashHudLayout.MIN_OPACITY, 1));
        }
    }

    private DashHudLayout.Widget widgetAt(double x, double y) {
        if (contains(selected, x, y)) return selected;
        for (int i = WIDGETS.length - 1; i >= 0; i--) {
            if (contains(WIDGETS[i], x, y)) return WIDGETS[i];
        }
        return null;
    }

    private boolean contains(DashHudLayout.Widget widget, double x, double y) {
        float left = widgetX(widget);
        float top = widgetY(widget);
        return hit(x, y, left, top, left + widgetWidth(widget), top + widgetHeight(widget));
    }

    private float widgetX(DashHudLayout.Widget widget) { return previewX + previewScale * DashHudLayout.positionX(widget, width, height); }
    private float widgetY(DashHudLayout.Widget widget) { return previewY + previewScale * DashHudLayout.positionY(widget, width, height); }
    private float widgetWidth(DashHudLayout.Widget widget) { return previewScale * DashHudLayout.displayedWidth(widget, width, height); }
    private float widgetHeight(DashHudLayout.Widget widget) { return previewScale * DashHudLayout.displayedHeight(widget, width, height); }

    private static boolean hit(double mouseX, double mouseY, float x, float y, float right, float bottom) {
        return mouseX >= x && mouseX < right && mouseY >= y && mouseY < bottom;
    }
}
