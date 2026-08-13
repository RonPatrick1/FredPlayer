package com.silveronstudios.fredplayer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class LyricsPhrase {
    final double startSeconds;
    final double endSeconds;
    final String text;
    final List<Word> words;

    private LyricsPhrase(double startSeconds, double endSeconds, String text, List<Word> words) {
        this.startSeconds = startSeconds;
        this.endSeconds = endSeconds;
        this.text = text;
        this.words = words;
    }

    static final class Word {
        final double timeSeconds;
        final String text;

        Word(double timeSeconds, String text) {
            this.timeSeconds = timeSeconds;
            this.text = text;
        }
    }

    // Picks a section to display: prefers "Original", otherwise whatever
    // section happens to be present (e.g. a translation-only sidecar).
    static List<LyricsPhrase> pickDisplaySection(JSONObject lyricsResponse) throws JSONException {
        JSONObject sections = lyricsResponse.optJSONObject("sections");
        if (sections == null || sections.length() == 0) {
            return new ArrayList<>();
        }
        JSONArray section = sections.optJSONArray("Original");
        if (section == null) {
            String firstKey = sections.keys().next();
            section = sections.optJSONArray(firstKey);
        }
        return section == null ? new ArrayList<>() : parseSection(section);
    }

    static List<LyricsPhrase> parseSection(JSONArray section) throws JSONException {
        List<LyricsPhrase> phrases = new ArrayList<>();
        for (int i = 0; i < section.length(); i++) {
            JSONObject phraseJson = section.getJSONObject(i);
            JSONArray wordsJson = phraseJson.getJSONArray("words");
            List<Word> words = new ArrayList<>();
            for (int w = 0; w < wordsJson.length(); w++) {
                JSONObject wordJson = wordsJson.getJSONObject(w);
                words.add(new Word(wordJson.getDouble("time"), wordJson.getString("text")));
            }
            phrases.add(new LyricsPhrase(
                    phraseJson.getDouble("start"),
                    phraseJson.getDouble("end"),
                    phraseJson.getString("text"),
                    words));
        }
        return phrases;
    }
}
