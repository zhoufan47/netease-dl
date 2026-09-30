package com.pewee.neteasemusic.utils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.pewee.neteasemusic.models.dtos.SingleMusicAnalysisRespDTO;

/**
 * 文件名模板渲染器
 * <p>
 * 采用业界通用的占位符模板（yt-dlp 的 -o 模板、foobar2000/MusicBee 的命名规则）：
 * 用 {占位符} 描述文件名，运行时替换为歌曲的实际信息。
 * <p>
 * 支持的占位符（中文名与英文别名等价，大小写不敏感）：
 * <ul>
 *     <li>{歌曲名} / {title}</li>
 *     <li>{歌手名} / {artist}</li>
 *     <li>{专辑名} / {album}</li>
 *     <li>{音轨号} / {track}（不足两位补零）</li>
 *     <li>{光盘号} / {disc}</li>
 *     <li>{年份} / {year}</li>
 *     <li>{歌曲ID} / {id}</li>
 * </ul>
 * 占位符取值为空时，会自动清理其周围多余的分隔符，避免出现 "歌曲名 - .flac" 这类文件名。
 *
 * @author pewee
 */
public final class FileNameFormatter {

    /** 默认模板：歌曲名 + 歌手名，用于区分同名歌曲 */
    public static final String DEFAULT_PATTERN = "{歌曲名} - {歌手名}";

    /** 预设模板，供页面下拉选择 */
    public static final String[] PRESET_PATTERNS = {
            "{歌曲名} - {歌手名}",
            "{歌曲名}",
            "{歌手名} - {歌曲名}",
            "{音轨号} - {歌曲名}",
            "{歌手名} - {专辑名} - {音轨号} - {歌曲名}",
            "{歌曲名} - {歌手名} - {歌曲ID}"
    };

    /** 占位符说明，供页面提示 */
    public static final String PLACEHOLDER_TIP =
            "可用占位符：{歌曲名} {歌手名} {专辑名} {音轨号} {光盘号} {年份} {歌曲ID}"
                    + "（也支持 {title} {artist} {album} {track} {disc} {year} {id}）";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");

    /** 所有已知占位符（小写），用于校验模板合法性 */
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
            "歌曲名", "title",
            "歌手名", "歌手", "artist",
            "专辑名", "专辑", "album",
            "音轨号", "track", "tracknumber",
            "光盘号", "disc",
            "年份", "year",
            "歌曲id", "id", "songid"));

    /** 模板中的分隔符字符：普通横线、下划线、各类破折号 */
    private static final String SEP_CHARS = "\\-_\\u2010-\\u2015\\u2212";

    /** 连续多个分隔符（中间可含空白）折叠成一个 " - " */
    private static final Pattern SEP_RUN =
            Pattern.compile("\\s*[" + SEP_CHARS + "][\\s" + SEP_CHARS + "]*[" + SEP_CHARS + "]\\s*");

    /** 首尾残留的分隔符 */
    private static final Pattern LEADING_SEP = Pattern.compile("^\\s*[" + SEP_CHARS + "]+\\s*");
    private static final Pattern TRAILING_SEP = Pattern.compile("\\s*[" + SEP_CHARS + "]+\\s*$");

    /** 空占位符导致的空括号 */
    private static final Pattern EMPTY_BRACKETS = Pattern.compile("[(\\[]\\s*[)\\]]");

    private static final Pattern BLANK_RUN = Pattern.compile("\\s{2,}");

    private FileNameFormatter() {
    }

    /**
     * 渲染文件名（不含扩展名）
     */
    public static String render(String pattern, SingleMusicAnalysisRespDTO song) {
        if (song == null) {
            return "";
        }
        String template = StringUtils.isBlank(pattern) ? DEFAULT_PATTERN : pattern.trim();
        Map<String, String> values = resolveValues(song);

        StringBuilder sb = new StringBuilder();
        Matcher matcher = PLACEHOLDER.matcher(template);
        int last = 0;
        while (matcher.find()) {
            sb.append(template, last, matcher.start());
            String key = matcher.group(1) == null ? "" : matcher.group(1).trim().toLowerCase(Locale.ROOT);
            String value = values.get(key);
            if (value == null) {
                // 未知占位符原样保留，方便用户发现拼写错误
                sb.append(matcher.group());
            } else {
                sb.append(value);
            }
            last = matcher.end();
        }
        sb.append(template, last, template.length());

        String name = FileUtils.sanitizeFileName(compact(sb.toString()));
        if (StringUtils.isBlank(name)) {
            // 所有占位符都取不到值，回退到歌曲名，避免生成空文件名
            name = FileUtils.sanitizeFileName(song.getName());
        }
        if (StringUtils.isBlank(name)) {
            name = "未知歌曲" + (song.getId() == null ? "" : song.getId());
        }
        return name;
    }

    /**
     * 校验并归一化模板，非法输入回退为默认模板
     */
    public static String normalize(String pattern) {
        if (StringUtils.isBlank(pattern)) {
            return DEFAULT_PATTERN;
        }
        return pattern.trim();
    }

    /**
     * 模板中是否至少包含一个已知占位符
     */
    public static boolean containsPlaceholder(String pattern) {
        if (StringUtils.isBlank(pattern)) {
            return false;
        }
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        while (matcher.find()) {
            String key = matcher.group(1) == null ? "" : matcher.group(1).trim().toLowerCase(Locale.ROOT);
            if (KEYS.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> resolveValues(SingleMusicAnalysisRespDTO song) {
        Map<String, String> values = new LinkedHashMap<>();
        put(values, song.getName(), "歌曲名", "title");
        put(values, song.getAr_name(), "歌手名", "歌手", "artist");
        put(values, song.getAl_name(), "专辑名", "专辑", "album");
        put(values, formatTrack(song.getTrack_number()), "音轨号", "track", "tracknumber");
        put(values, formatNumber(song.getDisc_number()), "光盘号", "disc");
        put(values, formatNumber(song.getYear()), "年份", "year");
        put(values, song.getId() == null ? "" : String.valueOf(song.getId()), "歌曲id", "id", "songid");
        return values;
    }

    private static void put(Map<String, String> values, String value, String... keys) {
        String v = value == null ? "" : value.trim();
        for (String key : keys) {
            values.put(key, v);
        }
    }

    /** 音轨号补零，与音乐库（Navidrome/Plex 等）的惯例保持一致 */
    private static String formatTrack(Integer track) {
        if (track == null || track <= 0) {
            return "";
        }
        return track < 100 ? String.format(Locale.ROOT, "%02d", track) : String.valueOf(track);
    }

    private static String formatNumber(Integer value) {
        if (value == null || value <= 0) {
            return "";
        }
        return String.valueOf(value);
    }

    /**
     * 清理因占位符取值为空而残留的分隔符和空白
     */
    private static String compact(String raw) {
        String s = raw.replaceAll("[\\r\\n\\t]", " ");
        s = BLANK_RUN.matcher(s).replaceAll(" ");
        s = SEP_RUN.matcher(s).replaceAll(" - ");
        s = LEADING_SEP.matcher(s).replaceAll("");
        s = TRAILING_SEP.matcher(s).replaceAll("");
        s = EMPTY_BRACKETS.matcher(s).replaceAll("");
        s = BLANK_RUN.matcher(s).replaceAll(" ");
        return s.trim();
    }
}
