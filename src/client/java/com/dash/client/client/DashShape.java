package com.dash.client.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public final class DashShape {
    private static final Identifier CORNERS = Identifier.fromNamespaceAndPath("dash-client", "textures/gui/round_corners.png");
    private static final int CORNER_SIZE = 128;
    private static final int TEXTURE_SIZE = CORNER_SIZE * 2;

    private DashShape() {}

    public static void fill(GuiGraphics graphics, int x, int y, int right, int bottom, int radius, int argb) {
        int width = right - x;
        int height = bottom - y;
        if (width <= 0 || height <= 0 || (argb >>> 24) == 0) return;
        int corner = Math.min(radius, Math.min(width, height) / 2);
        if (corner <= 0) {
            graphics.fill(x, y, right, bottom, argb);
            return;
        }

        if (width > 2 * corner) {
            graphics.fill(x + corner, y, right - corner, y + corner, argb);
            graphics.fill(x + corner, bottom - corner, right - corner, bottom, argb);
        }
        if (height > 2 * corner) {
            graphics.fill(x, y + corner, right, bottom - corner, argb);
        }
        corner(graphics, x, y, 0, 0, corner, argb);
        corner(graphics, right - corner, y, CORNER_SIZE, 0, corner, argb);
        corner(graphics, x, bottom - corner, 0, CORNER_SIZE, corner, argb);
        corner(graphics, right - corner, bottom - corner, CORNER_SIZE, CORNER_SIZE, corner, argb);
    }

    public static void frame(GuiGraphics graphics, int x, int y, int right, int bottom, int radius,
                             int edgeArgb, int fillArgb) {
        fill(graphics, x, y, right, bottom, radius, edgeArgb);
        fill(graphics, x + 1, y + 1, right - 1, bottom - 1, radius - 1, fillArgb);
    }

    private static void corner(GuiGraphics graphics, int x, int y, int u, int v, int radius, int argb) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, CORNERS, x, y, u, v, radius, radius,
                CORNER_SIZE, CORNER_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, argb);
    }
}
