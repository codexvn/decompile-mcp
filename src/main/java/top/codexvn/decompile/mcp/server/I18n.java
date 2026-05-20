package top.codexvn.decompile.mcp.server;

/**
 * 简单的中英文切换。默认中文，通过 mcp.lang 系统属性或 MCP_LANG 环境变量切换。
 * 示例：-Dmcp.lang=en 或 MCP_LANG=en
 */
public final class I18n {

    private I18n() {}

    public static final String ZH = "zh";
    public static final String EN = "en";

    public static String lang() {
        String p = System.getProperty("mcp.lang");
        if (p != null && !p.isBlank()) {
            return p.trim().toLowerCase();
        }
        String e = System.getenv("MCP_LANG");
        if (e != null && !e.isBlank()) {
            return e.trim().toLowerCase();
        }
        return ZH;
    }

    /** 根据当前语言选择中文或英文版本 */
    public static String zhEn(String zh, String en) {
        return EN.equals(lang()) ? en : zh;
    }
}
