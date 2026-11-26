package com.dash.client.client;

import com.dash.client.client.DashDashboardScreen.Icon;
import net.minecraft.client.gui.GuiGraphics;

final class DashDashboardIcons {
    private DashDashboardIcons() {}

    static void draw(GuiGraphics graphics, Icon icon, int x, int y, int color) {
        switch (icon) {
            case DASH -> {
                bar(graphics, x + 2, y + 1, 9, 2, color);
                bar(graphics, x + 1, y + 5, 8, 2, color);
                bar(graphics, x, y + 9, 7, 2, color);
            }
            case GRID -> {
                box(graphics, x, y, 4, color);
                box(graphics, x + 6, y, 4, color);
                box(graphics, x, y + 6, 4, color);
                box(graphics, x + 6, y + 6, 4, color);
            }
            case COMBAT -> {
                diagonal(graphics, x + 1, y + 1, 9, 1, color);
                diagonal(graphics, x + 9, y + 1, 9, -1, color);
                bar(graphics, x + 1, y + 7, 3, 1, color);
                bar(graphics, x + 7, y + 7, 3, 1, color);
            }
            case MOVE -> {
                bar(graphics, x + 1, y + 5, 9, 1, color);
                diagonal(graphics, x + 6, y + 2, 4, 1, color);
                diagonal(graphics, x + 9, y + 5, 4, -1, color);
            }
            case PLAYER -> {
                box(graphics, x + 3, y, 5, color);
                bar(graphics, x + 2, y + 7, 7, 1, color);
                bar(graphics, x + 1, y + 8, 9, 2, color);
            }
            case RENDER -> {
                outline(graphics, x, y + 1, 10, 7, color);
                bar(graphics, x + 3, y + 9, 5, 1, color);
            }
            case VISUAL -> {
                diagonal(graphics, x + 4, y + 1, 5, 1, color);
                diagonal(graphics, x + 8, y + 5, 5, -1, color);
                diagonal(graphics, x + 4, y + 1, 5, -1, color);
                diagonal(graphics, x, y + 5, 5, 1, color);
                box(graphics, x + 3, y + 4, 3, color);
            }
            case CLIENT -> {
                outline(graphics, x, y + 1, 10, 8, color);
                bar(graphics, x + 2, y + 4, 6, 1, color);
                bar(graphics, x + 2, y + 6, 3, 1, color);
            }
            case CONFIG -> {
                outline(graphics, x + 1, y, 8, 10, color);
                bar(graphics, x + 3, y + 3, 4, 1, color);
                bar(graphics, x + 3, y + 6, 4, 1, color);
            }
            case FRIEND -> {
                box(graphics, x + 1, y + 1, 4, color);
                box(graphics, x + 6, y + 2, 4, color);
                bar(graphics, x, y + 7, 6, 2, color);
                bar(graphics, x + 6, y + 8, 5, 1, color);
            }
            case PIN -> {
                ring(graphics, x + 2, y, 7, color);
                box(graphics, x + 4, y + 2, 3, color);
                diagonal(graphics, x + 2, y + 6, 4, 1, color);
                diagonal(graphics, x + 8, y + 6, 4, -1, color);
            }
            case SLIDERS -> {
                bar(graphics, x, y + 1, 10, 1, color);
                bar(graphics, x, y + 5, 10, 1, color);
                bar(graphics, x, y + 9, 10, 1, color);
                bar(graphics, x + 2, y, 2, 3, color);
                bar(graphics, x + 6, y + 4, 2, 3, color);
                bar(graphics, x + 3, y + 8, 2, 3, color);
            }
            case SEARCH -> {
                ring(graphics, x, y, 7, color);
                diagonal(graphics, x + 6, y + 6, 4, 1, color);
            }
            case STAR -> {
                bar(graphics, x + 4, y, 1, 10, color);
                bar(graphics, x, y + 4, 9, 1, color);
                diagonal(graphics, x + 2, y + 2, 5, 1, color);
                diagonal(graphics, x + 6, y + 2, 5, -1, color);
            }
            case LANGUAGE -> {
                outline(graphics, x, y + 1, 10, 9, color);
                bar(graphics, x + 4, y + 1, 1, 9, color);
                bar(graphics, x + 1, y + 5, 8, 1, color);
            }
            case PALETTE -> {
                ring(graphics, x, y, 10, color);
                box(graphics, x + 3, y + 3, 2, color);
                box(graphics, x + 6, y + 4, 2, color);
                box(graphics, x + 3, y + 6, 2, color);
            }
            case FULL -> {
                for (int i = 0; i < 2; i++) {
                    int edge = i * 9;
                    bar(graphics, x, y + edge, 3, 1, color);
                    bar(graphics, x + 7, y + edge, 3, 1, color);
                    bar(graphics, x + edge, y, 1, 3, color);
                    bar(graphics, x + edge, y + 7, 1, 3, color);
                }
            }
            case PREVIEW -> {
                outline(graphics, x, y + 1, 10, 9, color);
                diagonal(graphics, x + 6, y + 4, 2, -1, color);
                diagonal(graphics, x + 4, y + 5, 3, 1, color);
            }
            case ELEMENTS -> {
                for (int row = 0; row < 3; row++) {
                    box(graphics, x, y + row * 4, 2, color);
                    bar(graphics, x + 4, y + row * 4, 6, 1, color);
                }
            }
            case PAUSE -> {
                bar(graphics, x + 2, y + 1, 2, 9, color);
                bar(graphics, x + 6, y + 1, 2, 9, color);
            }
        }
    }

    private static void ring(GuiGraphics graphics, int x, int y, int size, int color) {
        bar(graphics, x + 2, y, size - 4, 1, color);
        bar(graphics, x + 2, y + size - 1, size - 4, 1, color);
        bar(graphics, x, y + 2, 1, size - 4, color);
        bar(graphics, x + size - 1, y + 2, 1, size - 4, color);
        box(graphics, x + 1, y + 1, 1, color);
        box(graphics, x + size - 2, y + 1, 1, color);
        box(graphics, x + 1, y + size - 2, 1, color);
        box(graphics, x + size - 2, y + size - 2, 1, color);
    }

    private static void outline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        bar(graphics, x, y, width, 1, color);
        bar(graphics, x, y + height - 1, width, 1, color);
        bar(graphics, x, y + 1, 1, height - 2, color);
        bar(graphics, x + width - 1, y + 1, 1, height - 2, color);
    }

    private static void diagonal(GuiGraphics graphics, int x, int y, int size, int direction, int color) {
        for (int step = 0; step < size; step++) box(graphics, x + step * direction, y + step, 1, color);
    }

    private static void box(GuiGraphics graphics, int x, int y, int size, int color) {
        bar(graphics, x, y, size, size, color);
    }

    private static void bar(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + height, color);
    }
}
