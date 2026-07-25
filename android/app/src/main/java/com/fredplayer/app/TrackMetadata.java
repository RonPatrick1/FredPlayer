package com.fredplayer.app;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

final class TrackMetadata {
    final String title;
    final String artist;
    final String album;

    private TrackMetadata(String title, String artist, String album) {
        this.title = clean(title);
        this.artist = clean(artist);
        this.album = clean(album);
    }

    static TrackMetadata from(Context context, String uriString) {
        String fallbackTitle = PlaylistStore.displayName(context, uriString);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (RemoteLibraryClient.isRemote(uriString)) {
                String token = PlaylistStore.loadServerToken(context);
                Map<String, String> headers = new HashMap<>();
                if (!token.isEmpty()) {
                    headers.put("Authorization", "Bearer " + token);
                }
                retriever.setDataSource(uriString, headers);
            } else {
                retriever.setDataSource(context, Uri.parse(uriString));
            }
            String title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            if (clean(title).isEmpty()) {
                title = fallbackTitle;
            }
            return new TrackMetadata(title, artist, album);
        } catch (RuntimeException ignored) {
            return new TrackMetadata(fallbackTitle, "", "");
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    String detailLine() {
        if (!artist.isEmpty() && !album.isEmpty()) {
            return artist + " - " + album;
        }
        if (!artist.isEmpty()) {
            return artist;
        }
        return album;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
