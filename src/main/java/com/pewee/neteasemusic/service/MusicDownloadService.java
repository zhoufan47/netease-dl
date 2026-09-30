package com.pewee.neteasemusic.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.pewee.neteasemusic.enums.CommonRespInfo;
import com.pewee.neteasemusic.enums.FileExistsStrategy;
import com.pewee.neteasemusic.exceptions.ServiceException;
import com.pewee.neteasemusic.models.dtos.AlbumAnalysisRespDTO;
import com.pewee.neteasemusic.models.dtos.DownloadTask;
import com.pewee.neteasemusic.models.dtos.PlaylistAnalysisRespDTO;
import com.pewee.neteasemusic.models.dtos.SingleMusicAnalysisRespDTO;
import com.pewee.neteasemusic.models.dtos.TrackDTO;
import com.pewee.neteasemusic.repository.TaskRepository;
import com.pewee.neteasemusic.utils.FileNameFormatter;
import com.pewee.neteasemusic.utils.FileUtils;
import com.pewee.neteasemusic.utils.HttpClientUtil;
import com.pewee.neteasemusic.utils.TagUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class MusicDownloadService implements InitializingBean {

    public static final ThreadPoolExecutor executor = new ThreadPoolExecutor(5, 5, 60, TimeUnit.MINUTES,
            new ArrayBlockingQueue<>(10000));

    @Resource
    private AnalysisService analysisService;

    @Value("${download.path}")
    private String path;

    private Boolean repeat = true;

    // 音质等级配置
    private volatile String qualityLevel = "lossless";

    // 歌单下载时是否将歌单名作为专辑信息写入标签
    private volatile Boolean playlistAsAlbum = false;

    // 文件命名模板，默认带上歌手名以区分同名歌曲
    private volatile String fileNamePattern = FileNameFormatter.DEFAULT_PATTERN;

    // 目标文件已存在时的处理策略
    private volatile FileExistsStrategy fileExistsStrategy = FileExistsStrategy.RENAME;

    private static final String QUALITY_FILE = "quality.txt";
    private static final String PLAYLIST_AS_ALBUM_FILE = "playlist_as_album.txt";
    private static final String NAMING_FILE = "naming.txt";
    private static final String FILE_EXISTS_FILE = "file_exists.txt";

    private BufferedWriter bw;
    private HashSet<Long> hs;
    private ArrayBlockingQueue<Long> queue;

    // 下载任务队列管理
    private final ConcurrentHashMap<String, DownloadTask> taskMap = new ConcurrentHashMap<>();

    // SQLite 持久化仓库
    private final TaskRepository taskRepository = new TaskRepository();

    @Override
    public void afterPropertiesSet() throws Exception {
        // 首先确保下载目录存在（必须最先执行）
        File pathDir = new File(path);
        if (!pathDir.exists()) {
            boolean created = pathDir.mkdirs();
            if (!created) {
                log.warn("无法创建下载目录: {}，将使用备用目录: {}", path, System.getProperty("user.home") + "/neteasemusic-dl/");
                path = System.getProperty("user.home") + "/neteasemusic-dl/";
                pathDir = new File(path);
                if (!pathDir.exists()) {
                    pathDir.mkdirs();
                }
            }
        }

        String repeatFilePath = path + "repeat";
        File repeatFile = new File(repeatFilePath);
        if (!repeatFile.exists()) {
            this.repeat = true;
        } else {
            try (FileInputStream fileInputStream = new FileInputStream(repeatFile)) {
                byte[] arr = new byte[1];
                fileInputStream.read(arr);
                String string = new String(arr);
                log.info("读取到repeat:{}", string);
                this.repeat = "1".equals(string);
            } catch (IOException e) {
                log.error("读取文件错误!", e);
            }
        }

        // 读取音质配置
        String quality = readConfigFile(QUALITY_FILE);
        if (StringUtils.isNotBlank(quality)) {
            this.qualityLevel = quality;
            log.info("读取到音质配置:{}", quality);
        }

        // 读取歌单作为专辑配置
        String playlistAsAlbumVal = readConfigFile(PLAYLIST_AS_ALBUM_FILE);
        if (StringUtils.isNotBlank(playlistAsAlbumVal)) {
            this.playlistAsAlbum = "1".equals(playlistAsAlbumVal);
            log.info("读取到playlistAsAlbum配置:{}", playlistAsAlbumVal);
        }

        // 读取文件命名模板
        String naming = readConfigFile(NAMING_FILE);
        if (StringUtils.isNotBlank(naming)) {
            this.fileNamePattern = FileNameFormatter.normalize(naming);
            log.info("读取到文件命名模板:{}", this.fileNamePattern);
        }

        // 读取文件已存在时的处理策略
        String fileExists = readConfigFile(FILE_EXISTS_FILE);
        if (StringUtils.isNotBlank(fileExists)) {
            this.fileExistsStrategy = FileExistsStrategy.of(fileExists, FileExistsStrategy.RENAME);
            log.info("读取到文件已存在策略:{}", this.fileExistsStrategy);
        }

        String idsFile = path + "ids.txt";
        File file = new File(idsFile);
        if (!file.exists()) {
            file.createNewFile();
        }
        bw = new BufferedWriter(new FileWriter(idsFile, true));
        hs = new HashSet<>();
        queue = new ArrayBlockingQueue<>(5000);

        long length = file.length();
        byte[] arr = new byte[(int) length];
        try (FileInputStream i = new FileInputStream(file)) {
            i.read(arr);
        }
        String origin = new String(arr);
        hs.addAll(Arrays.asList(origin.split(" ")).stream().map(String::trim).filter(StringUtils::isNotBlank)
                .map(Long::valueOf).collect(Collectors.toList()));
        log.info("读取到已下载记录{}条!", hs.size());

        // ===================== 初始化 SQLite 任务持久化 =====================
        taskRepository.init(path);
        restoreTasksFromDb();
    }

    /**
     * 从 SQLite 恢复任务记录
     * - COMPLETED / FAILED 任务原样恢复
     * - WAITING / DOWNLOADING 任务标记为 FAILED（因重启中断）
     */
    private void restoreTasksFromDb() {
        List<DownloadTask> dbTasks = taskRepository.findAll();
        if (dbTasks.isEmpty()) {
            log.info("SQLite 中无历史任务记录");
            return;
        }
        int interrupted = 0;
        for (DownloadTask task : dbTasks) {
            if (task.getStatus() == DownloadTask.Status.WAITING
                    || task.getStatus() == DownloadTask.Status.DOWNLOADING) {
                // 重启前未完成的任务标记为失败
                task.setStatus(DownloadTask.Status.FAILED);
                task.setErrorMessage("应用重启，任务中断");
                task.setCompleteTime(System.currentTimeMillis());
                taskRepository.save(task);
                interrupted++;
            }
            taskMap.put(task.getTaskId(), task);
        }
        log.info("从 SQLite 恢复了 {} 条任务记录（其中 {} 条因重启标记为失败）",
                dbTasks.size(), interrupted);
    }

    // ===================== 配置文件读写 =====================

    /**
     * 读取下载目录下的配置项，文件不存在或读取失败时返回 null
     */
    private String readConfigFile(String fileName) {
        File file = new File(path + fileName);
        if (!file.exists()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] arr = new byte[(int) file.length()];
            int read = fis.read(arr);
            if (read <= 0) {
                return null;
            }
            return new String(arr, 0, read, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            log.error("读取配置{}错误!", fileName, e);
            return null;
        }
    }

    /**
     * 将配置项持久化到下载目录，失败只记录日志，不影响下载
     */
    private void writeConfigFile(String fileName, String content) {
        File file = new File(path + fileName);
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (IOException e) {
            log.error("保存配置{}失败!", fileName, e);
        }
    }

    @Scheduled(cron = "*/5 * * * * ?")
    public void syncLocalFile() {
        if (!repeat && !queue.isEmpty()) {
            String join = " " + String.join(" ", queue.stream().map(String::valueOf).collect(Collectors.toList()));
            try {
                bw.append(join);
                bw.flush();
                queue.clear();
            } catch (IOException e) {
                log.error("写入文件失败!", e);
            }
        }
    }

    // ===================== 音质配置 =====================

    public String getQualityLevel() {
        return qualityLevel;
    }

    public void setQualityLevel(String level) {
        this.qualityLevel = level;
        // 持久化到文件
        writeConfigFile(QUALITY_FILE, level);
    }

    // ===================== 下载路径配置 =====================

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    // ===================== 歌单作为专辑配置 =====================

    public Boolean getPlaylistAsAlbum() {
        return this.playlistAsAlbum;
    }

    public void setPlaylistAsAlbum(Boolean playlistAsAlbum) {
        this.playlistAsAlbum = playlistAsAlbum;
        // 持久化到文件
        writeConfigFile(PLAYLIST_AS_ALBUM_FILE, playlistAsAlbum ? "1" : "0");
    }

    // ===================== 文件命名配置 =====================

    public String getFileNamePattern() {
        return this.fileNamePattern;
    }

    /**
     * 设置文件命名模板，模板非法时抛出参数错误
     */
    public void setFileNamePattern(String pattern) {
        String normalized = FileNameFormatter.normalize(pattern);
        if (!FileNameFormatter.containsPlaceholder(normalized)) {
            throw new ServiceException(CommonRespInfo.NOT_LEGAL_PARAM.getCode(),
                    "文件命名模板至少需要包含一个占位符，" + FileNameFormatter.PLACEHOLDER_TIP);
        }
        this.fileNamePattern = normalized;
        writeConfigFile(NAMING_FILE, normalized);
    }

    // ===================== 文件已存在策略配置 =====================

    public FileExistsStrategy getFileExistsStrategy() {
        return this.fileExistsStrategy;
    }

    public void setFileExistsStrategy(String strategy) {
        FileExistsStrategy parsed = FileExistsStrategy.of(strategy, FileExistsStrategy.RENAME);
        this.fileExistsStrategy = parsed;
        writeConfigFile(FILE_EXISTS_FILE, parsed.name());
    }

    // ===================== 重复下载配置 =====================

    public void setRepeat(Boolean repeat) {
        String repeatFile = path + "repeat";
        File file = new File(repeatFile);
        if (file.exists()) {
            file.delete();
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            file.createNewFile();
        } catch (IOException e) {
            log.error("创建文件错误!", e);
        }
        try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
            fileOutputStream.write(repeat ? "1".getBytes() : "0".getBytes());
            fileOutputStream.flush();
        } catch (IOException e) {
            log.error("写入文件错误!", e);
        }
        this.repeat = repeat;
    }

    public Boolean getRepeat() {
        return this.repeat;
    }

    // ===================== 下载队列查询 =====================

    public List<DownloadTask> getDownloadQueue() {
        List<DownloadTask> list = new ArrayList<>(taskMap.values());
        list.sort(Comparator.comparingLong(t -> -t.getCreateTime()));
        return list;
    }

    public List<DownloadTask> getRecentTasks(int limit) {
        return getDownloadQueue().stream().limit(limit).collect(Collectors.toList());
    }

    public long getWaitingCount() {
        return taskMap.values().stream()
                .filter(t -> t.getStatus() == DownloadTask.Status.WAITING || t.getStatus() == DownloadTask.Status.DOWNLOADING)
                .count();
    }

    /**
     * 获取下载统计数据
     */
    public java.util.Map<String, Object> getStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("total", taskRepository.countAll());
        stats.put("completed", taskRepository.countByStatus(DownloadTask.Status.COMPLETED));
        stats.put("failed", taskRepository.countByStatus(DownloadTask.Status.FAILED));
        stats.put("downloading", taskRepository.countByStatus(DownloadTask.Status.DOWNLOADING));
        stats.put("waiting", taskRepository.countByStatus(DownloadTask.Status.WAITING));
        stats.put("skipped", taskRepository.countByStatus(DownloadTask.Status.SKIPPED));
        return stats;
    }

    /**
     * 清理已完成和失败的任务
     * @return 清理的任务数量
     */
    public int clearFinishedTasks() {
        int before = taskMap.size();
        taskMap.entrySet().removeIf(entry -> {
            DownloadTask.Status status = entry.getValue().getStatus();
            return status == DownloadTask.Status.COMPLETED || status == DownloadTask.Status.FAILED
                    || status == DownloadTask.Status.SKIPPED;
        });
        int removed = before - taskMap.size();
        // 同步删除 SQLite
        taskRepository.deleteByStatuses("COMPLETED", "FAILED", "SKIPPED");
        log.info("清理了 {} 个已完成/失败的任务", removed);
        return removed;
    }

    /**
     * 清理所有任务（包括正在下载的）
     * @return 清理的任务数量
     */
    public int clearAllTasks() {
        int before = taskMap.size();
        taskMap.clear();
        // 同步删除 SQLite
        taskRepository.deleteAll();
        log.info("清理了全部 {} 个任务", before);
        return before;
    }

    // ===================== 下载逻辑 =====================

    private String getType(String url) {
        return url.substring(url.lastIndexOf("."), url.indexOf("?"));
    }

    /**
     * 由音频的最终路径推导歌词文件名，保证歌词与音频始终同名（含自动重命名后的 " (2)" 后缀）
     */
    private String toLyricName(Path audioPath, String audioExt, String fallbackBaseName) {
        String audioName = audioPath.getFileName() == null ? null : audioPath.getFileName().toString();
        if (audioName == null) {
            return fallbackBaseName + ".lrc";
        }
        if (audioExt != null && !audioExt.isEmpty() && audioName.endsWith(audioExt)) {
            return audioName.substring(0, audioName.length() - audioExt.length()) + ".lrc";
        }
        int dot = audioName.lastIndexOf('.');
        return (dot > 0 ? audioName.substring(0, dot) : audioName) + ".lrc";
    }

    public void downloadSingleSongV2(Long id) {
        doDownloadSingleSongV2(id, this.path, DownloadTask.Type.SINGLE, null, null, null, null, 0);
    }

    public void downloadPlaylistV2(Long id) {
        PlaylistAnalysisRespDTO analysisPlaylist = analysisService.analyzePlaylist(id);
        if (200 != analysisPlaylist.getStatus()) {
            throw new ServiceException(CommonRespInfo.SYS_ERROR);
        }
        List<TrackDTO> tracks = analysisPlaylist.getPlaylist().getTracks();
        String parentName = analysisPlaylist.getPlaylist().getName();
        String playlistCreator = analysisPlaylist.getPlaylist().getCreator();
        for (int i = 0; i < tracks.size(); i++) {
            TrackDTO trackDTO = tracks.get(i);
            final int trackIndex = i + 1;
            executor.execute(() -> {
                doDownloadSingleSongV2(trackDTO.getId(),
                        this.path + "歌单/" + FileUtils.getValidatedPathName(parentName) + "/",
                        DownloadTask.Type.PLAYLIST, id, parentName, parentName, playlistCreator, trackIndex);
            });
        }
    }

    public void downloadAlbumV2(Long id) {
        AlbumAnalysisRespDTO analysisAlbum = analysisService.analyzeAlbum(id);
        if (200 != analysisAlbum.getStatus()) {
            throw new ServiceException(CommonRespInfo.SYS_ERROR);
        }
        List<TrackDTO> tracks = analysisAlbum.getAlbum().getSongs();
        String parentName = analysisAlbum.getAlbum().getName();
        for (TrackDTO trackDTO : tracks) {
            executor.execute(() -> {
                doDownloadSingleSongV2(trackDTO.getId(),
                        this.path + "专辑/" + FileUtils.getValidatedPathName(parentName) + "/",
                        DownloadTask.Type.ALBUM, id, parentName, null, null, 0);
            });
        }
    }

    private void doDownloadSingleSongV2(Long id, String dirPath, DownloadTask.Type type,
                                         Long parentId, String parentName,
                                         String overrideAlbum, String overrideAlbumArtist,
                                         int playlistTrackIndex) {
        if (!repeat && hs.contains(id)) {
            log.info("歌曲id: {} 已存在,跳过!", id);
            return;
        }

        // 先获取歌曲信息以创建任务
        SingleMusicAnalysisRespDTO analysisSingleMusic = analysisService.analyzeSingleSong(id, qualityLevel);
        if (analysisSingleMusic == null || 200 != analysisSingleMusic.getStatus()) {
            log.error("解析歌曲id: {} 失败!", id);
            DownloadTask failedTask = DownloadTask.create(id, "未知歌曲(id:" + id + ")", "", "",
                    type, parentId, parentName);
            failedTask.setStatus(DownloadTask.Status.FAILED);
            failedTask.setErrorMessage("歌曲解析失败");
            failedTask.setCompleteTime(System.currentTimeMillis());
            taskMap.put(failedTask.getTaskId(), failedTask);
            taskRepository.save(failedTask);
            return;
        }

        // 创建下载任务并加入队列
        DownloadTask task = DownloadTask.create(id, analysisSingleMusic.getName(),
                analysisSingleMusic.getAr_name(), analysisSingleMusic.getAl_name(),
                type, parentId, parentName);
        taskMap.put(task.getTaskId(), task);
        taskRepository.save(task);

        task.setStatus(DownloadTask.Status.DOWNLOADING);
        task.markDownloadStart();
        taskRepository.save(task);

        CloseableHttpResponse response = null;
        // 目标文件是否由本次下载新建，用于失败时清理残留的半截文件
        boolean newFile = false;
        Path targetPath = null;
        try {
            String fileName = FileNameFormatter.render(fileNamePattern, analysisSingleMusic);
            String fileExt = getType(analysisSingleMusic.getUrl());
            Path dir = Paths.get(dirPath);
            targetPath = FileUtils.resolveTargetPath(dir, fileName, fileExt, fileExistsStrategy);
            if (targetPath == null) {
                // 策略为跳过且文件已存在
                log.info("歌曲id: {} 的目标文件已存在,按配置跳过: {}", id, dir.resolve(fileName + fileExt));
                markSkipped(task, "文件已存在，已跳过: " + dir.resolve(fileName + fileExt).getFileName());
                return;
            }
            newFile = !Files.exists(targetPath);

            log.info("开始将歌曲: {} 写入目录: {}", targetPath.getFileName(), dirPath);

            // 使用 HttpClient 直接下载，获取 Content-Length 用于进度追踪
            HttpGet httpGet = new HttpGet(analysisSingleMusic.getUrl());
            httpGet.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            response = HttpClientUtil.getInstance().execute(httpGet);
            HttpEntity entity = response.getEntity();

            // 获取文件总大小
            long totalSize = entity.getContentLength();
            if (totalSize <= 0) {
                // 从 Header 尝试获取
                Header contentLengthHeader = response.getFirstHeader("Content-Length");
                if (contentLengthHeader != null) {
                    try {
                        totalSize = Long.parseLong(contentLengthHeader.getValue());
                    } catch (NumberFormatException ignored) {}
                }
            }
            task.setTotalSize(totalSize > 0 ? totalSize : 0);

            // 带进度追踪的文件写入
            Files.createDirectories(targetPath.getParent());
            // 覆盖策略下允许写入已存在的文件，其余策略只允许新建
            StandardOpenOption[] openOptions = fileExistsStrategy == FileExistsStrategy.OVERWRITE
                    ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE};
            try (InputStream is = entity.getContent();
                 OutputStream os = Files.newOutputStream(targetPath, openOptions)) {
                byte[] buffer = new byte[8192];
                long bytesDownloaded = 0;
                int len;
                int progressUpdateCounter = 0;
                while ((len = is.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                    bytesDownloaded += len;
                    progressUpdateCounter++;
                    // 每 32 次循环（约 256KB）更新一次进度，避免过于频繁
                    if (progressUpdateCounter >= 32) {
                        task.updateProgress(bytesDownloaded, totalSize);
                        progressUpdateCounter = 0;
                    }
                }
                os.flush();
                // 最终更新进度为 100%
                task.updateProgress(bytesDownloaded, totalSize);
            }

            // 下载失败时清理不完整的文件
            if (task.getBytesDownloaded() > 0 && totalSize > 0
                    && task.getBytesDownloaded() < totalSize) {
                throw new IOException("下载不完整: 已下载 " + task.getBytesDownloaded()
                        + " / " + totalSize + " 字节");
            }

            File file = targetPath.toFile();

            // 写入完整ID3标签（含封面、专辑艺术家、光盘号、音轨号、年份）
            String albumName = analysisSingleMusic.getAl_name();
            String albumArtist = analysisSingleMusic.getAlbum_artist();
            Integer discNumber = analysisSingleMusic.getDisc_number();
            Integer trackNumber = analysisSingleMusic.getTrack_number();
            // 歌单下载时，若开启playlistAsAlbum配置，使用歌单名和创建者覆盖专辑信息
            if (type == DownloadTask.Type.PLAYLIST && playlistAsAlbum
                    && overrideAlbum != null && !overrideAlbum.isEmpty()) {
                albumName = overrideAlbum;
                if (overrideAlbumArtist != null && !overrideAlbumArtist.isEmpty()) {
                    albumArtist = overrideAlbumArtist;
                }
                // 使用歌单内曲目序号作为音轨号，光盘号统一为1
                if (playlistTrackIndex > 0) {
                    trackNumber = playlistTrackIndex;
                }
                discNumber = 1;
                //将年份信息设置为当前年份
                String currYear = String.valueOf(Calendar.getInstance().get(Calendar.YEAR));
                analysisSingleMusic.setYear(Integer.valueOf(currYear));
            }
            TagUtils.setTags(file, analysisSingleMusic.getName(), analysisSingleMusic.getAr_name(),
                    albumName, albumArtist,
                    discNumber, trackNumber,
                    analysisSingleMusic.getYear(), analysisSingleMusic.getPic());

            log.info("将歌曲: {} 写入目录: {} 已完成!", targetPath.getFileName(), dirPath);

            // 写入歌词（歌词与音频同名，写入失败不影响下载结果）
            try {
                if (StringUtils.isNotBlank(analysisSingleMusic.getLyric())) {
                    // 用音频的最终文件名推导歌词名，自动重命名后两者仍保持一致
                    Path lyricPath = dir.resolve(toLyricName(targetPath, fileExt, fileName));
                    // 覆盖策略下歌词也一并覆盖
                    if (fileExistsStrategy == FileExistsStrategy.OVERWRITE) {
                        Files.deleteIfExists(lyricPath);
                    }
                    log.info("开始将歌词: {} 写入目录: {}", lyricPath.getFileName(), dirPath);
                    FileUtils.writeToFile(lyricPath, analysisSingleMusic.getLyric().getBytes("UTF-8"));
                    log.info("将歌词: {} 写入目录: {} 已完成!", lyricPath.getFileName(), dirPath);
                }
            } catch (Exception e) {
                log.warn("歌词写入失败: {}", fileName, e);
            }

            task.setStatus(DownloadTask.Status.COMPLETED);
            task.setCompleteTime(System.currentTimeMillis());
            task.setSpeed(0);
            task.setEta(0);
            taskRepository.save(task);

            hs.add(id);
            queue.offer(id);

        } catch (Exception e) {
            log.error("下载歌曲id: {} 失败!", id, e);
            // 清理下载中断残留的半截文件（仅清理本次新建的文件）
            if (newFile && targetPath != null) {
                try {
                    Files.deleteIfExists(targetPath);
                } catch (IOException ignore) {
                    log.warn("清理不完整的文件失败: {}", targetPath);
                }
            }
            task.setStatus(DownloadTask.Status.FAILED);
            task.setErrorMessage(buildErrorMessage(e, targetPath));
            task.setCompleteTime(System.currentTimeMillis());
            task.setSpeed(0);
            task.setEta(0);
            taskRepository.save(task);
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    log.warn("关闭 HTTP 响应失败", e);
                }
            }
        }
    }

    /**
     * 标记任务为「已跳过」（文件已存在且策略为跳过）
     */
    private void markSkipped(DownloadTask task, String message) {
        task.setStatus(DownloadTask.Status.SKIPPED);
        task.setErrorMessage(message);
        task.setCompleteTime(System.currentTimeMillis());
        task.setSpeed(0);
        task.setEta(0);
        taskRepository.save(task);
        hs.add(task.getSongId());
        queue.offer(task.getSongId());
    }

    /**
     * 把异常转换成用户能看懂的失败原因
     * <p>
     * JDK 抛出的原始信息往往只有一个文件路径（如 FileAlreadyExistsException 的 message 就是路径本身），
     * 直接展示会让人完全不知道失败原因，这里按异常类型翻译成明确的原因描述。
     */
    private String buildErrorMessage(Throwable e, Path targetPath) {
        String target = targetPath == null ? null : targetPath.toString();
        if (e instanceof FileAlreadyExistsException) {
            return "目标文件已存在: " + target + "（可在下载配置中把「文件已存在时」改为自动重命名或覆盖）";
        }
        if (e instanceof AccessDeniedException) {
            return "没有写入权限: " + target;
        }
        if (e instanceof NoSuchFileException) {
            return "目录不存在或已被删除: " + target;
        }
        if (e instanceof FileSystemException) {
            // 磁盘空间不足、设备错误等，JDK 的 message 常常为空
            return "文件系统错误(" + e.getClass().getSimpleName() + "): " + describeCause(e, target);
        }
        if (e instanceof SocketTimeoutException) {
            return "下载超时，网络连接不稳定";
        }
        if (e instanceof ConnectException) {
            return "无法连接到下载服务器: " + describeCause(e, target);
        }
        if (e instanceof UnknownHostException) {
            return "网络异常，域名解析失败: " + e.getMessage();
        }
        if (e instanceof IOException) {
            return "下载失败: " + describeCause(e, target);
        }
        return "下载失败(" + e.getClass().getSimpleName() + "): " + describeCause(e, target);
    }

    /**
     * 提取可读的错误原因：优先异常自身的 message，其次根因的 message，最后回退到目标路径/异常类型
     */
    private String describeCause(Throwable e, String target) {
        if (e == null) {
            return target == null ? "未知原因" : target;
        }
        if (StringUtils.isNotBlank(e.getMessage())) {
            return e.getMessage();
        }
        Throwable cause = e.getCause();
        while (cause != null) {
            if (StringUtils.isNotBlank(cause.getMessage())) {
                return cause.getMessage();
            }
            cause = cause.getCause();
        }
        if (target != null) {
            return target;
        }
        return e.getClass().getSimpleName();
    }
}
