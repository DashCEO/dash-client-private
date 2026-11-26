package com.dash.client.client;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.StringDecomposer;

public final class NameProtect {
    private static final String ALIAS = "Player";
    private static final String USERNAME_CHAR = "[a-zA-Z0-9_]";
    private static boolean enabled;
    private static Pattern usernamePattern;
    private static String alias = ALIAS;

    private NameProtect() {
    }

    public static boolean isEnabled() {
        return enabled && !SaveMode.isPaused();
    }

    public static void setEnabled(boolean newValue) {
        if (enabled == newValue) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        String username = client.getUser().getName();
        alias = ALIAS.equalsIgnoreCase(username) ? "Anonymous" : ALIAS;
        usernamePattern = newValue && !username.isEmpty()
                ? Pattern.compile("(?<!" + USERNAME_CHAR + ")" + Pattern.quote(username)
                        + "(?!" + USERNAME_CHAR + ")", Pattern.CASE_INSENSITIVE)
                : null;
        enabled = newValue;

        // Regenerate the display lines from the original chat history at the current width.
        client.gui.getChat().rescaleChat();
    }

    public static Component mask(Component source) {
        MutableComponent replacement = replacement(source);
        return replacement != null ? replacement : source;
    }

    public static MutableComponent mask(MutableComponent source) {
        MutableComponent replacement = replacement(source);
        return replacement != null ? replacement : source;
    }

    private static MutableComponent replacement(Component source) {
        Pattern pattern = usernamePattern;
        if (SaveMode.isPaused() || pattern == null || source == null) {
            return null;
        }

        String text = source.getString();
        if (text.indexOf('\u00a7') >= 0) {
            text = StringDecomposer.getPlainText(source);
        }
        Matcher matches = pattern.matcher(text);
        if (!matches.find()) {
            return null;
        }

        // A server can split one username across multiple styled components.
        MaskedText result = new MaskedText(matches);
        source.visit(result::append, Style.EMPTY);
        return result.output;
    }

    private static final class MaskedText implements FormattedCharSink {
        private final Matcher matches;
        private final MutableComponent output = Component.empty();
        private final StringBuilder run = new StringBuilder();
        private String sourceRun;
        private int runPosition;
        private int position;
        private boolean hasMatch = true;

        private MaskedText(Matcher matches) {
            this.matches = matches;
        }

        private Optional<Void> append(Style style, String text) {
            sourceRun = text;
            runPosition = 0;
            StringDecomposer.iterateFormatted(text, style, this);
            run.append(text, runPosition, text.length());
            if (!run.isEmpty()) {
                output.append(Component.literal(run.toString()).setStyle(style));
                run.setLength(0);
            }
            return Optional.empty();
        }

        @Override
        public boolean accept(int index, Style style, int codePoint) {
            // Keep the raw formatting codes so the chat color setting still applies.
            run.append(sourceRun, runPosition, index);
            int length = Character.charCount(codePoint);
            runPosition = index + length;
            if (!hasMatch || position < matches.start()) {
                run.append(sourceRun, index, runPosition);
            } else if (position == matches.start()) {
                run.append(alias);
            }

            position += length;
            if (hasMatch && position >= matches.end()) {
                hasMatch = matches.find();
            }
            return true;
        }
    }
}
