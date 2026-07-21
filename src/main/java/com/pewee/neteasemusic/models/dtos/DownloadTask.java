package com.pewee.neteasemusic.models.dtos;

import lombok.Data;

@Data
public class DownloadTask {

    public enum Status {
        WAITING, DOWNLOADING, COMPLETED, FAILED
    }

    public enum Type {
        SINGLE, ALBUM, PLAYLIST
    }

    private String taskId;
    private Long songId;
    private String songName;
    private String artist;
    private String album;
    private Status status;
    private Type type;
    private Long parentId;
    private String parentName;
    private String errorMessage;
    private Long createTime;
    private Long completeTime;

    public static DownloadTask create(Long songId, String songName, String artist, String album,
                                       Type type, Long parentId, String parentName) {
        DownloadTask task = new DownloadTask();
        task.setTaskId(java.util.UUID.randomUUID().toString().replace("-", ""));
        task.setSongId(songId);
        task.setSongName(songName);
        task.setArtist(artist);
        task.setAlbum(album);
        task.setStatus(Status.WAITING);
        task.setType(type);
        task.setParentId(parentId);
        task.setParentName(parentName);
        task.setCreateTime(System.currentTimeMillis());
        return task;
    }
}
