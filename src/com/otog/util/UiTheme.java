package com.otog.util;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumnModel;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
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
        LABEL_BOLD,
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
    private static volatile Border cachedTopDivider;

    private static final Insets BUTTON_MARGIN = new Insets(5, 12, 5, 12);
    private static final Insets COMPACT_BUTTON_MARGIN = new Insets(4, 9, 4, 9);
    private static final Insets INPUT_MARGIN = new Insets(3, 6, 3, 6);

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
            case LABEL_BOLD -> font(baseFamily, Font.BOLD, 12);
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
                t -> BorderFactory.createTitledBorder(
                        BorderFactory.createEtchedBorder(),
                        t,
                        TitledBorder.LEFT,
                        TitledBorder.TOP,
                        font(FontRole.SECTION_HEADER),
                        color(ColorRole.PRIMARY)
                ));
    }

    public static Border bottomDividerBorder() {
        Border current = cachedBottomDivider;
        if (current == null) {
            current = BorderFactory.createMatteBorder(0, 0, 1, 0, color(ColorRole.BORDER));
            cachedBottomDivider = current;
        }
        return current;
    }

    public static Border topDividerBorder() {
        Border current = cachedTopDivider;
        if (current == null) {
            current = BorderFactory.createMatteBorder(1, 0, 0, 0, color(ColorRole.BORDER));
            cachedTopDivider = current;
        }
        return current;
    }

    public static void stylePrimaryButton(JButton button) {
        if (button == null) {
            return;
        }
        button.setFont(font(FontRole.LABEL_BOLD));
        button.setMargin(BUTTON_MARGIN);
        button.setFocusPainted(false);
    }

    public static void styleSecondaryButton(JButton button) {
        if (button == null) {
            return;
        }
        button.setFont(font(FontRole.LABEL));
        button.setMargin(COMPACT_BUTTON_MARGIN);
        button.setFocusPainted(false);
    }

    public static void styleFormLabel(JLabel label) {
        if (label == null) {
            return;
        }
        label.setFont(font(FontRole.LABEL));
    }

    public static void styleInputField(JComponent field) {
        if (field == null) {
            return;
        }
        field.setFont(font(FontRole.BODY));
        if (field instanceof JTextComponent textComponent) {
            textComponent.setMargin(INPUT_MARGIN);
        }
    }

    public static void styleTable(JTable table, int... preferredColumnWidths) {
        if (table == null) {
            return;
        }
        table.setRowHeight(OtogConfig.getInstance().getTableRowHeight());
        table.setFont(font(FontRole.LABEL));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setGridColor(color(ColorRole.BORDER));
        table.setIntercellSpacing(new Dimension(6, 2));
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        JTableHeader header = table.getTableHeader();
        if (header != null) {
            header.setReorderingAllowed(false);
            header.setFont(font(FontRole.LABEL_BOLD));
        }
        if (preferredColumnWidths != null && preferredColumnWidths.length > 0) {
            TableColumnModel columnModel = table.getColumnModel();
            int count = Math.min(preferredColumnWidths.length, columnModel.getColumnCount());
            for (int i = 0; i < count; i++) {
                if (preferredColumnWidths[i] > 0) {
                    columnModel.getColumn(i).setPreferredWidth(preferredColumnWidths[i]);
                }
            }
        }
    }

    private static final DefaultTableCellRenderer RIGHT_ALIGNED_CELL_RENDERER = createRightAlignedRenderer();

    private static DefaultTableCellRenderer createRightAlignedRenderer() {
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
        renderer.setHorizontalAlignment(SwingConstants.RIGHT);
        return renderer;
    }

    public static void alignTableColumnsRight(JTable table, int... columnIndices) {
        if (table == null || columnIndices == null || columnIndices.length == 0) {
            return;
        }
        TableColumnModel columnModel = table.getColumnModel();
        int columnCount = columnModel.getColumnCount();
        for (int index : columnIndices) {
            if (index >= 0 && index < columnCount) {
                columnModel.getColumn(index).setCellRenderer(RIGHT_ALIGNED_CELL_RENDERER);
            }
        }
    }

    public static void clearCache() {
        FONT_POOL.clear();
        synchronized (COLOR_POOL) {
            COLOR_POOL.clear();
        }
        TITLED_BORDER_POOL.clear();
        cachedBottomDivider = null;
        cachedTopDivider = null;
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
