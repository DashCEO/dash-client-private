package com.dash.client.client;

import com.dash.client.client.mixin.FontProviderAccessor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

public final class DashTypography {
    private static final FontDescription UI = new FontDescription.Resource(
            Identifier.fromNamespaceAndPath("dash-client", "ui"));
    private static Font source;
    private static Font dash;

    private DashTypography() {}

    static float snap(float position, int guiScale) {
        return Math.round(position * guiScale) / (float) guiScale;
    }

    public static Font font(Font vanilla) {
        if (vanilla == dash) return dash;
        if (vanilla != source) {
            Font.Provider original = ((FontProviderAccessor) vanilla).dash$getProvider();
            // The original provider resolves the current font sets after resource reloads.
            dash = new Font(new Font.Provider() {
                @Override
                public GlyphSource glyphs(FontDescription description) {
                    return original.glyphs(FontDescription.DEFAULT.equals(description) ? UI : description);
                }

                @Override
                public EffectGlyph effect() {
                    return original.effect();
                }
            });
            source = vanilla;
        }
        return dash;
    }
}
