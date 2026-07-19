package com.alexey.autoremix;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

final class MusicLibrary {
    private static final String TAG = "AutoRemixLibrary";
    private MusicLibrary() {}

    static List<Track> scan(Context context) {
        List<Track> result = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.MIME_TYPE
        };
        String selection = MediaStore.Audio.Media.DURATION + ">=?";
        String[] args = {"45000"};
        try (Cursor cursor = resolver.query(collection, projection, selection, args,
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) return result;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                long duration = cursor.getLong(durationCol);
                if (duration < 45_000) continue;
                Uri uri = ContentUris.withAppendedId(collection, id);
                result.add(new Track(id, uri, cursor.getString(titleCol), cursor.getString(artistCol),
                        duration, cursor.getString(mimeCol)));
            }
        } catch (SecurityException denied) {
            throw denied;
        } catch (RuntimeException error) {
            Log.w(TAG, "event=scan_failed reason=" + error.getClass().getSimpleName(), error);
        }
        return result;
    }
}
