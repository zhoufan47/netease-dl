package com.pewee.neteasemusic.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

import com.pewee.neteasemusic.enums.CommonRespInfo;
import com.pewee.neteasemusic.enums.FileExistsStrategy;
import com.pewee.neteasemusic.exceptions.ServiceException;


public class FileUtils {

	/** 文件名非法字符：Windows + POSIX 通用限制（\ / : * ? " < > | 与控制字符） */
	private static final Pattern ILLEGAL_FILENAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1f]");

	/** 文件名最大长度，预留出 " (99)" 与扩展名的空间（常见文件系统上限 255 字节） */
	private static final int MAX_FILENAME_LENGTH = 180;

	/** 自动重命名的最大尝试次数 */
	private static final int MAX_RENAME_SEQ = 999;
	
	public static Path writeToFile(Path path, byte[] bytes) {
		try {
	        if (!Files.exists(path)) {
	        	// 创建,追加到文件
	            Files.createDirectories(path.getParent());
	            Files.createFile(path);
		        Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	        } 
	        return path;
	    } catch (Exception e) {
	        throw new RuntimeException("文件写入错误", e);
	    } finally {
	    	bytes = null;
	    }
    }
	
	/**
	 * 清洗目录名，保留历史行为（/ 替换为 #），同时过滤其它非法字符
	 */
	public static String getValidatedPathName(String name) {
		return sanitizeFileName(name == null ? "" : name.replace("/", "#"));
	}

	/**
	 * 清洗文件名片段：替换文件系统非法字符、折叠空白、去掉结尾的点、限制长度
	 */
	public static String sanitizeFileName(String name) {
		if (name == null) {
			return "";
		}
		String s = ILLEGAL_FILENAME_CHARS.matcher(name).replaceAll("_");
		s = s.replaceAll("\\s+", " ").trim();
		// Windows 下文件名不能以点或空格结尾
		s = s.replaceAll("[.\\s]+$", "");
		if (s.length() > MAX_FILENAME_LENGTH) {
			s = s.substring(0, MAX_FILENAME_LENGTH).trim();
		}
		return s;
	}

	/**
	 * 按策略解析出最终的目标文件路径
	 *
	 * @param dir       目标目录
	 * @param baseName  文件名（不含扩展名）
	 * @param ext       扩展名，如 ".flac"
	 * @param strategy  文件已存在时的处理策略
	 * @return 目标路径；策略为 SKIP 且文件已存在时返回 null
	 * @throws IOException 自动重命名超过上限
	 */
	public static Path resolveTargetPath(Path dir, String baseName, String ext, FileExistsStrategy strategy)
			throws IOException {
		String suffix = ext == null ? "" : ext;
		Path target = dir.resolve(baseName + suffix);
		if (strategy == null) {
			strategy = FileExistsStrategy.RENAME;
		}
		switch (strategy) {
			case OVERWRITE:
				return target;
			case SKIP:
				return Files.exists(target) ? null : target;
			case RENAME:
			default:
				if (!Files.exists(target)) {
					return target;
				}
				for (int i = 2; i <= MAX_RENAME_SEQ; i++) {
					Path candidate = dir.resolve(baseName + " (" + i + ")" + suffix);
					if (!Files.exists(candidate)) {
						return candidate;
					}
				}
				throw new IOException("文件已存在且无法自动重命名（重名数量超过 " + MAX_RENAME_SEQ + "）: "
						+ dir.resolve(baseName + suffix));
		}
	}
	
	public static Path writeToFile(Path path, InputStream is) {
		try {
	        if (!Files.exists(path)) {
	        	// 创建,追加到文件
	            Files.createDirectories(path.getParent());
	            //Files.copy(is, path, StandardCopyOption.REPLACE_EXISTING);
	            try (OutputStream os = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
	                byte[] buffer = new byte[8192];
	                int len;
	                while ((len = is.read(buffer)) != -1) {
	                    os.write(buffer, 0, len);
	                }
	            }
	        } 
	        return path;
	    } catch (Exception e) {
	    	try {
				Files.deleteIfExists(path);
			} catch (IOException e1) {
				throw new ServiceException(CommonRespInfo.SYS_ERROR,e1);
			}
	        throw new RuntimeException("文件写入错误", e);
	    } finally {
	    	try {
				if (null != is) {
					is.close();
				}
			} catch (IOException e) {
				throw new ServiceException(CommonRespInfo.SYS_ERROR,e);
			}
	    }
    }
}
