package com.dash.client.client;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringDecomposer;

final class StaffList {
    private static final int MAX_NAMES = 5;
    private static final Pattern ROLE = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_])(?:staff|owner|admin(?:istrator)?|mod(?:erator)?|helper|"
                    + "адмін(?:істратор)?|админ(?:истратор)?|модер(?:атор)?|стаж[её]р|куратор|хелпер)"
                    + "(?![\\p{L}\\p{N}_])");

    private static boolean enabled;
    private static ClientPacketListener lastConnection;
    private static List<String> visibleNames = List.of();
    private static int ticksUntilScan;

    private StaffList() {}

    static boolean isEnabled() { return enabled; }

    static void toggle() {
        enabled = !enabled;
        reset();
    }

    static void tick(Minecraft client) {
        if (SaveMode.isPaused()) return;
        var connection = client.getConnection();
        if (!enabled || connection == null || client.level == null) {
            reset();
            return;
        }
        if (lastConnection != connection) {
            reset();
            lastConnection = connection;
        }
        if (ticksUntilScan-- > 0) return;
        ticksUntilScan = 19;

        List<String> found = new ArrayList<>();
        for (PlayerInfo player : connection.getListedOnlinePlayers()) {
            String name = player.getProfile().name();
            if (hasStaffRole(player, name)) {
                found.add(name);
            }
        }
        found.sort(String.CASE_INSENSITIVE_ORDER);
        visibleNames = found;
    }

    private static boolean hasStaffRole(PlayerInfo player, String name) {
        var team = player.getTeam();
        if (team != null && ROLE.matcher(StringDecomposer.getPlainText(team.getPlayerPrefix())).find()) {
            return true;
        }
        Component display = player.getTabListDisplayName();
        if (display == null) return false;

        String label = StringDecomposer.getPlainText(display);
        int namePosition = label.indexOf(name);
        return namePosition > 0 && ROLE.matcher(label.substring(0, namePosition)).find();
    }

    static int draw(GuiGraphics graphics, Minecraft client, int x, int y) {
        if (visibleNames.isEmpty() && HudSettings.hideEmpty()) return y;
        var font = DashTypography.font(client.font);
        int rows = Math.min(MAX_NAMES, visibleNames.size());
        DashHud.panel(graphics, x, y, x + 104, y + 13 + 10 * rows);
        graphics.drawString(font, "STAFF · TAB", x + 6, y + 3, HudSettings.color(HudSettings.accent()), false);
        String count = Integer.toString(visibleNames.size());
        graphics.drawString(font, count, x + 98 - font.width(count), y + 3,
                HudSettings.color(HudSettings.secondary()), false);
        for (int index = 0; index < rows; index++) {
            Component name = NameProtect.mask(Component.literal(visibleNames.get(index)));
            graphics.drawString(font, DashHud.fit(font, name.getString(), 92), x + 6, y + 13 + 10 * index,
                    HudSettings.color(DashHud.textColor()), false);
        }
        return y + 15 + 10 * rows;
    }

    static void reset() {
        visibleNames = List.of();
        lastConnection = null;
        ticksUntilScan = 0;
    }
}
