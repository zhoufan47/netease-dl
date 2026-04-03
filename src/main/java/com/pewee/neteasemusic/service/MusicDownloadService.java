package com.pewee.neteasemusic.service;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.pewee.neteasemusic.config.AnalysisConfig;
import com.pewee.neteasemusic.enums.CommonRespInfo;
import com.pewee.neteasemusic.exceptions.ServiceException;
import com.pewee.neteasemusic.models.dtos.AlbumAnalysisRespDTO;
import com.pewee.neteasemusic.models.dtos.PlaylistAnalysisRespDTO;
import com.pewee.neteasemusic.models.dtos.SingleMusicAnalysisRespDTO;
import com.pewee.neteasemusic.models.dtos.TrackDTO;
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
	private AnalysisConfig config;

	@Value("${download.path}")
	private String path;

	private Boolean repeat = true;

	private BufferedWriter bw;

	private HashSet<Long> hs;

	private ArrayBlockingQueue<Long> queue;

	@Override
	public void afterPropertiesSet() throws Exception {
		String repeatFilePath = path + "repeat";
		File repeatFile = new File(repeatFilePath);
		if (!repeatFile.exists()) {
			this.repeat = true;
		} else {
			try (FileInputStream fileInputStream = new FileInputStream(repeatFile)) {
				byte[] arr = new byte[1];
				fileInputStream.read(arr);
				fileInputStream.close();
				String string = new String(arr);
				log.info("读取到repeat:{}",string);
				if ("1".equals(string)) {
					this.repeat = true;
				} else {
					this.repeat = false;
				}
			} catch (IOException e) {
				log.error("读取文件错误!", e);
			}
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
	}

	// @Scheduled(cron = "0 */5 * * * ?")
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

	@Resource
	private AnalysisService analysisService;

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	private static final String SINGLE_SONG = "Song_V1";
	private static final String PLAY_LIST = "Playlist";
	private static final String ALBUM = "Album";

	private String getType(String url) {
		return url.substring(url.lastIndexOf("."), url.indexOf("?"));
	}

	public void downloadAlbum(Long id) {
		AlbumAnalysisRespDTO analysisAlbum = analysisAlbum(id);
		if (200 != analysisAlbum.getStatus()) {
			throw new ServiceException(CommonRespInfo.SYS_ERROR);
		}
		List<TrackDTO> tracks = analysisAlbum.getAlbum().getSongs();
		for (TrackDTO trackDTO : tracks) {
			executor.execute(() -> {
				downloadSingleSong(trackDTO.getId());
			});
		}
	}

	public void downloadPlaylist(Long id) {
		PlaylistAnalysisRespDTO analysisPlaylist = analysisPlaylist(id);
		if (200 != analysisPlaylist.getStatus()) {
			throw new ServiceException(CommonRespInfo.SYS_ERROR);
		}
		List<TrackDTO> tracks = analysisPlaylist.getPlaylist().getTracks();
		for (TrackDTO trackDTO : tracks) {
			executor.execute(() -> {
				downloadSingleSong(trackDTO.getId());
			});
		}
	}

	public void downloadSingleSong(Long id) {
		if (!repeat && hs.contains(id)) {
			log.info("歌曲id: {} 已存在,跳过!", id);
			return;
		}
		SingleMusicAnalysisRespDTO analysisSingleMusic = analysisSingleMusic(id);
		if (200 != analysisSingleMusic.getStatus()) {
			throw new ServiceException(CommonRespInfo.SYS_ERROR);
		}
		String dir = path + analysisSingleMusic.getAl_name();
		String fileName = analysisSingleMusic.getName();
		log.info("开始将歌曲: {} 写入目录: {}", fileName, dir);
		FileUtils.writeToFile(Paths.get(dir, fileName + getType(analysisSingleMusic.getUrl())),
				HttpClientUtil.getInputStream(analysisSingleMusic.getUrl(), null));
		File file = Paths.get(dir, fileName + getType(analysisSingleMusic.getUrl())).toFile();
		TagUtils.setTags(file, analysisSingleMusic.getName(), analysisSingleMusic.getAr_name(),
				analysisSingleMusic.getAl_name());
		log.info("将歌曲: {} 写入目录: {} 已完成!", fileName, dir);
		try {
			log.info("开始将歌词: {} 写入目录: {}", fileName, dir);
			FileUtils.writeToFile(Paths.get(dir, fileName + ".lrc"),
					analysisSingleMusic.getLyric().getBytes("UTF-8"));
			log.info("将歌词: {} 写入目录: {} 已完成!", fileName, dir);
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		hs.add(id);
		queue.offer(id);
	}

	private AlbumAnalysisRespDTO analysisAlbum(Long id) {
		log.info("解析专辑id:{}", id);
		StringBuilder stringBuilder = new StringBuilder("");
		stringBuilder.append("http://").append(config.getIp())
				.append(":").append(config.getPort()).append("/").append(ALBUM)
				.append("?type=json&level=lossless").append("&id=").append(id);
		String executeGet = null;
		try {
			executeGet = HttpClientUtil.executeGet(stringBuilder.toString(), null, null);
		} catch (IOException | URISyntaxException e) {
			log.error("调用解析失败!!", e);
			throw new ServiceException(CommonRespInfo.SYS_ERROR, e);
		}
		AlbumAnalysisRespDTO respDTO = JSON.parseObject(executeGet, AlbumAnalysisRespDTO.class);
		log.info("调用解析返回:{}", JSON.toJSONString(respDTO));
		return respDTO;
	}

	private PlaylistAnalysisRespDTO analysisPlaylist(Long id) {
		log.info("解析歌单id:{}", id);
		StringBuilder stringBuilder = new StringBuilder("");
		stringBuilder.append("http://").append(config.getIp())
				.append(":").append(config.getPort()).append("/").append(PLAY_LIST)
				.append("?type=json&level=lossless").append("&id=").append(id);
		String executeGet = null;
		try {
			executeGet = HttpClientUtil.executeGet(stringBuilder.toString(), null, null);
		} catch (IOException | URISyntaxException e) {
			log.error("调用解析失败!!", e);
			throw new ServiceException(CommonRespInfo.SYS_ERROR, e);
		}
		PlaylistAnalysisRespDTO respDTO = JSON.parseObject(executeGet, PlaylistAnalysisRespDTO.class);
		log.info("调用解析返回:{}", JSON.toJSONString(respDTO));
		return respDTO;
	}

	private SingleMusicAnalysisRespDTO analysisSingleMusic(Long ids) {
		log.info("解析音乐id:{}", ids);
		StringBuilder stringBuilder = new StringBuilder("");
		stringBuilder.append("http://").append(config.getIp())
				.append(":").append(config.getPort()).append("/").append(SINGLE_SONG)
				.append("?type=json&level=lossless").append("&ids=").append(ids);
		String executeGet = null;
		try {
			executeGet = HttpClientUtil.executeGet(stringBuilder.toString(), null, null);
		} catch (IOException | URISyntaxException e) {
			log.error("调用解析失败!!", e);
			throw new ServiceException(CommonRespInfo.SYS_ERROR, e);
		}
		SingleMusicAnalysisRespDTO respDTO = JSON.parseObject(executeGet, SingleMusicAnalysisRespDTO.class);
		log.info("调用解析返回:{}", JSON.toJSONString(respDTO));
		return respDTO;
	}

	public void downloadSingleSongV2(Long id) {
		if (!repeat && hs.contains(id)) {
			log.info("歌曲id: {} 已存在,跳过!", id);
			return;
		}
		SingleMusicAnalysisRespDTO analysisSingleMusic = analysisService.analyzeSingleSong(id, "lossless");
		if (200 != analysisSingleMusic.getStatus()) {
			throw new ServiceException(CommonRespInfo.SYS_ERROR);
		}
		String dir = path;
		String fileName = analysisSingleMusic.getName();
		log.info("开始将歌曲: {} 写入目录: {}", fileName, dir);
		FileUtils.writeToFile(Paths.get(dir, fileName + getType(analysisSingleMusic.getUrl())),
				HttpClientUtil.getInputStream(analysisSingleMusic.getUrl(), null));
		File file = Paths.get(dir, fileName + getType(analysisSingleMusic.getUrl())).toFile();
		TagUtils.setTags(file, analysisSingleMusic.getName(), analysisSingleMusic.getAr_name(),
				analysisSingleMusic.getAl_name());
		log.info("将歌曲: {} 写入目录: {} 已完成!", fileName, dir);
		try {
			log.info("开始将歌词: {} 写入目录: {}", fileName, dir);
			FileUtils.writeToFile(Paths.get(dir, fileName + ".lrc"),
					analysisSingleMusic.getLyric().getBytes("UTF-8"));
			log.info("将歌词: {} 写入目录: {} 已完成!", fileName, dir);
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		hs.add(id);
		queue.offer(id);
	}

	public void doDownloadSingleSongV2(Long id, String path) {
		if (!repeat && hs.contains(id)) {
			log.info("歌曲id: {} 已存在,跳过!", id);
			return;
		}
		SingleMusicAnalysisRespDTO analysisSingleMusic = analysisService.analyzeSingleSong(id, "lossless");
		if (200 != analysisSingleMusic.getStatus()) {
			throw new ServiceException(CommonRespInfo.SYS_ERROR);
		}
		String dir = path;
		String fileName = analysisSingleMusic.getName();
		log.info("开始将歌曲: {} 写入目录: {}", fileName, dir);
		FileUtils.writeToFile(Paths.get(dir, fileName + getType(analysisSingleMusic.getUrl())),
				HttpClientUtil.getInputStream(analysisSingleMusic.getUrl(), null));
		File file = Paths.get(dir, fileName + getType(analysisSingleMusic.getUrl())).toFile();
		TagUtils.setTags(file, analysisSingleMusic.getName(), analysisSingleMusic.getAr_name(),
				analysisSingleMusic.getAl_name());
		log.info("将歌曲: {} 写入目录: {} 已完成!", fileName, dir);
		try {
			log.info("开始将歌词: {} 写入目录: {}", fileName, dir);
			FileUtils.writeToFile(Paths.get(dir, fileName + ".lrc"),
					analysisSingleMusic.getLyric().getBytes("UTF-8"));
			log.info("将歌词: {} 写入目录: {} 已完成!", fileName, dir);
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		hs.add(id);
		queue.offer(id);
	}

	public void downloadPlaylistV2(Long id) {
		PlaylistAnalysisRespDTO analysisPlaylist = analysisService.analyzePlaylist(id);
		if (200 != analysisPlaylist.getStatus()) {
			throw new ServiceException(CommonRespInfo.SYS_ERROR);
		}
		List<TrackDTO> tracks = analysisPlaylist.getPlaylist().getTracks();
		for (TrackDTO trackDTO : tracks) {
			executor.execute(() -> {
				doDownloadSingleSongV2(trackDTO.getId(), this.path + "歌单/"
						+ FileUtils.getValidatedPathName(analysisPlaylist.getPlaylist().getName()) + "/");
			});
		}
	}

	public void downloadAlbumV2(Long id) {
		AlbumAnalysisRespDTO analysisAlbum = analysisService.analyzeAlbum(id);
		if (200 != analysisAlbum.getStatus()) {
			throw new ServiceException(CommonRespInfo.SYS_ERROR);
		}
		List<TrackDTO> tracks = analysisAlbum.getAlbum().getSongs();
		for (TrackDTO trackDTO : tracks) {
			executor.execute(() -> {
				doDownloadSingleSongV2(trackDTO.getId(),
						this.path + "专辑/" + FileUtils.getValidatedPathName(analysisAlbum.getAlbum().getName()) + "/");
			});
		}

	}

	public void setRepeat(Boolean repeat) {
		String repeatFile = path + "repeat";
		File file = new File(repeatFile);
		if (file.exists()) {
			file.delete();
		}
		try {
			file.createNewFile();
		} catch (IOException e) {
			log.error("创建文件错误!", e);
		}
		try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
			if (repeat) {
				fileOutputStream.write("1".getBytes());
			} else {
				fileOutputStream.write("0".getBytes());
			}
			fileOutputStream.flush();
			fileOutputStream.close();
		} catch (IOException e) {
			log.error("创建文件错误!", e);
		}
		;
		this.repeat = repeat;
	}

	public Boolean getRepeat() {
		return this.repeat;
	}

}
