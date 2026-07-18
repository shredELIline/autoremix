package com.alexey.autoremix;

import android.net.Uri;

final class Track {
    final long id;
    final Uri uri;
    final String title;
    final String artist;
    final long durationMs;
    final String mime;

    Track(long id, Uri uri, String title, String artist, long durationMs, String mime) {
        this.id = id;
        this.uri = uri;
        this.title = title == null || title.trim().isEmpty() ? "Без названия" : title;
        this.artist = artist == null || artist.trim().isEmpty() || "<unknown>".equalsIgnoreCase(artist)
                ? "Неизвестный исполнитель" : artist;
        this.durationMs = durationMs;
        this.mime = mime == null ? "audio/*" : mime;
    }

    String displayName() {
        return artist + " — " + title;
    }
}
