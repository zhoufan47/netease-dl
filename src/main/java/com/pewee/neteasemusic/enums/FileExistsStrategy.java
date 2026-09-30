package com.pewee.neteasemusic.enums;

/**
 * 目标文件已存在时的处理策略
 * <p>
 * 参考主流下载工具的通用做法（浏览器下载 / yt-dlp / 下载管理器），
 * 默认使用「自动重命名」，避免同名文件直接导致下载失败。
 *
 * @author pewee
 */
public enum FileExistsStrategy {

    /**
     * 自动重命名：在文件名后追加 " (2)"、" (3)" ... 保留两份文件
     */
    RENAME("自动重命名"),

    /**
     * 跳过：认为已经下载过，不再重复下载
     */
    SKIP("跳过"),

    /**
     * 覆盖：直接覆盖已有的同名文件
     */
    OVERWRITE("覆盖");

    private final String desc;

    FileExistsStrategy(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 宽松解析，无法识别时返回默认值
     */
    public static FileExistsStrategy of(String value, FileExistsStrategy defaultStrategy) {
        if (value == null || value.trim().isEmpty()) {
            return defaultStrategy;
        }
        for (FileExistsStrategy strategy : values()) {
            if (strategy.name().equalsIgnoreCase(value.trim())) {
                return strategy;
            }
        }
        return defaultStrategy;
    }
}
