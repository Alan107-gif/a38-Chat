package de.corecosmetic.a38chat;

final class ProfileColorPalette {
    static final int COLUMNS = 6;

    private static final String[] HEX_COLORS = {
            "#000000", "#374151", "#6B7280", "#9CA3AF", "#D1D5DB", "#FFFFFF",
            "#7F1D1D", "#DC2626", "#F87171", "#9D174D", "#EC4899", "#F9A8D4",
            "#7C2D12", "#EA580C", "#FDBA74", "#78350F", "#CA8A04", "#FDE047",
            "#14532D", "#16A34A", "#86EFAC", "#365314", "#84CC16", "#D9F99D",
            "#164E63", "#0891B2", "#67E8F9", "#1E3A8A", "#2563EB", "#93C5FD",
            "#4C1D95", "#7C3AED", "#C4B5FD", "#701A75", "#C026D3", "#F0ABFC"
    };

    private ProfileColorPalette() {
    }

    static int[] colors() {
        int[] colors = new int[HEX_COLORS.length];
        for (int i = 0; i < HEX_COLORS.length; i++) {
            colors[i] = 0xFF000000 | Integer.parseInt(HEX_COLORS[i].substring(1), 16);
        }
        return colors;
    }
}
