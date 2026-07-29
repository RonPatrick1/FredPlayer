package com.silveronstudios.fredplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;

import org.junit.Test;

public class ServerUrlPolicyTest {
    @Test
    public void acceptsHttpsAndNormalizesTrailingSlashes() throws Exception {
        assertEquals(
                "https://music.example.com/fredplayer",
                ServerUrlPolicy.normalizeAndValidate(
                        " https://music.example.com/fredplayer/// ",
                        false));
    }

    @Test
    public void rejectsPublicCleartextServers() {
        assertThrows(IOException.class, () -> ServerUrlPolicy.normalizeAndValidate(
                "http://music.example.com",
                true));
    }

    @Test
    public void permitsLoopbackHttpOnlyForDevelopment() throws Exception {
        assertEquals(
                "http://127.0.0.1:8787",
                ServerUrlPolicy.normalizeAndValidate("http://127.0.0.1:8787", true));
        assertThrows(IOException.class, () -> ServerUrlPolicy.normalizeAndValidate(
                "http://127.0.0.1:8787",
                false));
    }

    @Test
    public void rejectsEmbeddedCredentialsQueriesAndFragments() {
        assertThrows(IOException.class, () -> ServerUrlPolicy.normalizeAndValidate(
                "https://token@example.com",
                false));
        assertThrows(IOException.class, () -> ServerUrlPolicy.normalizeAndValidate(
                "https://example.com?token=secret",
                false));
        assertThrows(IOException.class, () -> ServerUrlPolicy.normalizeAndValidate(
                "https://example.com/#fragment",
                false));
    }
}
