package com.otog.util;

import javax.swing.BorderFactory;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Font;
import java.util.EnumMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Structural Flyweight registry sharing immutable {@link Font}, {@link Color}, and
 * {@link Border} instances across all Swing forms and panels. Intrinsic visual tokens
 * are sourced from {@link OtogConfig} to eliminate hardcoded styling literals.
 */
public final class UiTheme {

    public enum FontRole {
        BANNER,
        TITLE,
        SECTION_HEADER,
        BALANCE,
        BODY,
        BODY_ITALIC,
        LABEL,
        SMALL,
        MONO
    }

    public enum ColorRole {
        MUTED("ui.color.muted", "#5A626E"),
        SUCCESS("ui.color.success", "#116644"),
        ERROR("ui.color.error", "#B02626"),
        WARNING("ui.color.warning", "#B45F0A"),
        INFO("ui.color.info", "#46505F"),
        PRIMARY("ui.color.primary", "#2D5A8C"),
        BORDER("ui.color.border", "#D7DCE2");

        private final String configKey;
        private final String defaultHex;

        ColorRole(String configKey, String defaultHex) {
            this.configKey = configKey;
            this.defaultHex = defaultHex;
        }

        public String getConfigKey() {
            return configKey;
        }

        public String getDefaultHex() {
            return defaultHex;
        }
    }

    /**
     * Immutable composite key identifying a shared {@link Font} flyweight.
     */
    public record FontKey(String family, int style, int size) {
    }

    private static final ConcurrentHashMap<FontKey, Font> FONT_POOL = new ConcurrentHashMap<>();
    private static final EnumMap<ColorRole, Color> COLOR_POOL = new EnumMap<>(ColorRole.class);
    private static final ConcurrentHashMap<String, Border> TITLED_BORDER_POOL = new ConcurrentHashMap<>();
    private static volatile Border cachedBottomDivider;

    private UiTheme() {
    }

    public static Font font(FontRole role) {
        if (role == null) {
            return font(FontRole.BODY);
        }
        OtogConfig config = OtogConfig.getInstance();
        String baseFamily = config.getFontFamily();
        String monoFamily = config.getMonoFontFamily();
        return switch (role) {
            case BANNER -> font(baseFamily, Font.BOLD, 16);
            case TITLE -> font(baseFamily, Font.BOLD, 14);
            case SECTION_HEADER -> font(baseFamily, Font.BOLD, 13);
            case BALANCE -> font(baseFamily, Font.BOLD, 18);
            case BODY -> font(baseFamily, Font.PLAIN, 13);
            case BODY_ITALIC -> font(baseFamily, Font.ITALIC, 13);
            case LABEL -> font(baseFamily, Font.PLAIN, 12);
            case SMALL -> font(baseFamily, Font.PLAIN, 11);
            case MONO -> font(monoFamily, Font.PLAIN, 12);
        };
    }

    public static Font font(String family, int style, int size) {
        String effectiveFamily = (family != null && !family.trim().isEmpty())
                ? family.trim()
                : OtogConfig.getInstance().getFontFamily();
        FontKey key = new FontKey(effectiveFamily, style, Math.max(8, size));
        return FONT_POOL.computeIfAbsent(key, k -> new Font(k.family(), k.style(), k.size()));
    }

    public static Color color(ColorRole role) {
        ColorRole effective = role != null ? role : ColorRole.INFO;
        synchronized (COLOR_POOL) {
            return COLOR_POOL.computeIfAbsent(effective, r -> {
                String hex = OtogConfig.getInstance().getColorHex(r.getConfigKey(), r.getDefaultHex());
                return parseColor(hex, r.getDefaultHex());
            });
        }
    }

    public static Border titledBorder(String title) {
        String safeTitle = title != null ? title : "";
        return TITLED_BORDER_POOL.computeIfAbsent(safeTitle,
                t -> BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), t));
    }

    public static Border bottomDividerBorder() {
        Border current = cachedBottomDivider;
        if (current == null) {
            current = BorderFactory.createMatteBorder(0, 0, 1, 0, color(ColorRole.BORDER));
            cachedBottomDivider = current;
        }
        return current;
    }

    public static void clearCache() {
        FONT_POOL.clear();
        synchronized (COLOR_POOL) {
            COLOR_POOL.clear();
        }
        TITLED_BORDER_POOL.clear();
        cachedBottomDivider = null;
    }

    public static int cachedFontCount() {
        return FONT_POOL.size();
    }

    public static int cachedColorCount() {
        synchronized (COLOR_POOL) {
            return COLOR_POOL.size();
        }
    }

    private static Color parseColor(String hex, String fallbackHex) {
        try {
            return Color.decode(hex.trim());
        } catch (RuntimeException ex) {
            return Color.decode(fallbackHex);
        }
    }
}
