package org.openshell.ui;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.util.UIScale;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;

/**
 * 集中式动态主题引擎
 * 提供自适应深色 (FlatDarkLaf) 与浅色 (FlatLightLaf) 的语义化色彩与通用边框规范，
 * 彻底解决浅色模式下硬编码深色造成的“黑底黑字”、“黑白杂交”等界面缺陷。
 */
public class ThemeUtils {
    static { FlatLaf.registerCustomDefaultsSource("org.openshell.ui.theme"); }
    private static final String CODE_FONT = chooseCodeFont();

    public static void install(boolean dark) {
        boolean installed = dark ? FlatDarkLaf.setup() : FlatLightLaf.setup();
        if (!installed) throw new IllegalStateException("无法加载 OpenShell 界面主题");
    }

    public static Font uiFont(int style, int size) {
        Font base = UIManager.getFont("defaultFont");
        if (base == null) base = new Font(Font.DIALOG, Font.PLAIN, 12);
        return base.deriveFont(style, (float) UIScale.scale(size));
    }

    public static Font monoFont(int style, int size) {
        // StyleContext supplies a composite font: Consolas for Latin with CJK
        // fallback, instead of missing Chinese glyphs from a physical Font.
        return javax.swing.text.StyleContext.getDefaultStyleContext().getFont(CODE_FONT, style, UIScale.scale(size));
    }

    private static String chooseCodeFont() {
        var available = java.util.Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String family : new String[]{"Consolas", "Cascadia Mono", "JetBrains Mono", "Menlo", "DejaVu Sans Mono"})
            if (available.contains(family)) return family;
        return Font.MONOSPACED;
    }

    public static void applyTextRendering(Graphics2D graphics) {
        Object desktopHints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
        if (desktopHints instanceof java.util.Map<?, ?> hints) graphics.addRenderingHints(hints);
        else graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    }

    public static void toolbarButton(AbstractButton button) {
        button.putClientProperty(com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE,
                com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.setFont(uiFont(Font.PLAIN, 12));
        button.setFocusable(false);
    }

    public static boolean isDark() {
        return FlatLaf.isLafDark();
    }

    /** 主窗口及底层面板背景色 */
    public static Color getBg() {
        return isDark() ? new Color(24, 26, 32) : new Color(246, 248, 250);
    }

    /** 卡片、内容列表、文本区等白板背景色 */
    public static Color getCardBg() {
        return isDark() ? new Color(30, 34, 43) : new Color(255, 255, 255);
    }

    /** 工具栏、标题头、标签栏次级背景色 */
    public static Color getHeaderBg() {
        return isDark() ? new Color(34, 38, 48) : new Color(238, 241, 246);
    }

    /** 悬浮、激活态、输入框背景色 */
    public static Color getElevatedBg() {
        return isDark() ? new Color(40, 45, 58) : new Color(242, 244, 248);
    }

    /** 边框、分割线颜色 */
    public static Color getBorderColor() {
        return isDark() ? new Color(48, 54, 68) : new Color(210, 216, 226);
    }

    /** 次要边框/微弱细线 */
    public static Color getSubtleBorderColor() {
        return isDark() ? new Color(40, 44, 54) : new Color(225, 230, 238);
    }

    /** 主标题与正文主要文字颜色 */
    public static Color getTextPrimary() {
        return isDark() ? new Color(230, 235, 245) : new Color(33, 37, 44);
    }

    /** 次级辅助说明文字颜色 */
    public static Color getTextSecondary() {
        return isDark() ? new Color(160, 166, 178) : new Color(100, 108, 120);
    }

    /** 弱化/占位符提示文字颜色 */
    public static Color getTextMuted() {
        return isDark() ? new Color(110, 118, 130) : new Color(145, 152, 163);
    }

    /** 品牌主色 (蓝) */
    public static Color getAccent() {
        return isDark() ? new Color(88, 166, 255) : new Color(9, 105, 218);
    }

    /** 品牌主色背景 (浅蓝微透) */
    public static Color getAccentBg() {
        return isDark() ? new Color(28, 48, 76) : new Color(234, 242, 255);
    }

    /** 成功色 (绿) */
    public static Color getSuccess() {
        return isDark() ? new Color(63, 185, 80) : new Color(26, 127, 55);
    }

    /** 警告色 (橙黄) */
    public static Color getWarning() {
        return isDark() ? new Color(210, 153, 34) : new Color(154, 103, 0);
    }

    /** 危险/错误色 (红) */
    public static Color getDanger() {
        return isDark() ? new Color(248, 81, 73) : new Color(207, 34, 46);
    }

    /** 紫色/磁盘色 */
    public static Color getPurple() {
        return isDark() ? new Color(198, 120, 221) : new Color(130, 80, 223);
    }

    /** 表格交替行条纹色彩 */
    public static Color getTableAlternateBg() {
        return isDark() ? new Color(27, 30, 38) : new Color(250, 251, 253);
    }

    /** 表格选中背景色 */
    public static Color getSelectionBg() {
        return isDark() ? new Color(44, 62, 86) : new Color(204, 228, 255);
    }

    /** 终端控制台背景色 (终端即便在浅色系统下，保持高对比极客黑或深炭灰最佳体验) */
    public static Color getTerminalBg() {
        return new Color(16, 19, 23);
    }

    /** 终端文本颜色 */
    public static Color getTerminalFg() {
        return new Color(225, 230, 240);
    }

    /** 便捷创建上/下/左/右单边微边框 */
    public static Border createMatteBorder(int top, int left, int bottom, int right) {
        return BorderFactory.createMatteBorder(top, left, bottom, right, getBorderColor());
    }

    /** 便捷创建圆角卡片边框 */
    public static Border createCardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(getBorderColor(), 1, true),
                new EmptyBorder(0, 0, 0, 0)
        );
    }
}
