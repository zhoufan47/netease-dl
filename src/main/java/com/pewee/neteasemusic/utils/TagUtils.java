package com.pewee.neteasemusic.utils;

import java.io.File;
import java.io.IOException;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TagUtils {

    public static void setTags(File file, String title, String artist, String album) {
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

            audioFile.commit();
            log.info("Successfully set tags for file: {}", file.getName());
        } catch (Exception e) {
            log.error("Failed to set tags for file: " + file.getName(), e);
        }
    }

    public static void main(String[] args) {
        // Validation code for manual testing
        // File testFile = new File("path/to/test.mp3");
        // setTags(testFile, "Test Title", "Test Artist", "Test Album");
    }
}
