package com.pewee.neteasemusic.utils;

import java.io.File;
import java.io.InputStream;
import java.net.URL;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TagUtils {

    /**
     * 写入完整ID3标签（含封面图片）
     */
    public static void setTags(File file, String title, String artist, String album,
                               String albumArtist, Integer discNumber, Integer trackNumber,
                               Integer year, String coverUrl) {
        try {
            AudioFile audioFile = AudioFileIO.read(file);
            Tag tag = audioFile.getTag();
            if (tag == null) {
                tag = audioFile.createDefaultTag();
                audioFile.setTag(tag);
            }

            if (title != null) {
                tag.setField(FieldKey.TITLE, title);
            }
            if (artist != null) {
                tag.setField(FieldKey.ARTIST, artist);
            }
            if (album != null) {
                tag.setField(FieldKey.ALBUM, album);
            }
            if (albumArtist != null) {
                tag.setField(FieldKey.ALBUM_ARTIST, albumArtist);
            }
            if (discNumber != null) {
                tag.setField(FieldKey.DISC_NO, String.valueOf(discNumber));
            }
            if (trackNumber != null) {
                tag.setField(FieldKey.TRACK, String.valueOf(trackNumber));
            }
            if (year != null) {
                tag.setField(FieldKey.YEAR, String.valueOf(year));
            }
            // 下载封面并写入
            if (coverUrl != null && !coverUrl.isEmpty()) {
                try {
                    byte[] coverBytes = downloadImage(coverUrl);
                    if (coverBytes != null && coverBytes.length > 0) {
                        Artwork artwork = ArtworkFactory.getNew();
                        artwork.setBinaryData(coverBytes);
                        artwork.setMimeType("image/jpeg");
                        tag.setField(artwork);
                    }
                } catch (Exception e) {
                    log.warn("封面图片下载或写入失败: {}", e.getMessage());
                }
            }

            audioFile.commit();
            log.info("Successfully set tags for file: {}", file.getName());
        } catch (Exception e) {
            log.error("Failed to set tags for file: " + file.getName(), e);
        }
    }

    /**
     * 兼容旧方法（不写封面和扩展标签）
     */
    public static void setTags(File file, String title, String artist, String album) {
        setTags(file, title, artist, album, null, null, null, null, null);
    }

    private static byte[] downloadImage(String imageUrl) {
        try (InputStream is = new URL(imageUrl).openStream()) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("下载封面图片失败: {}", e.getMessage());
            return null;
        }
    }
}
