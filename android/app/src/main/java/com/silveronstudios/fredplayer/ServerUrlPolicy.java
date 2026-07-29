package com.silveronstudios.fredplayer;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

final class ServerUrlPolicy {
    private ServerUrlPolicy() {
    }

    static String normalizeAndValidate(String baseUrl, boolean allowDevelopmentLoopback)
            throws IOException {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        URL parsed;
        try {
            parsed = new URL(normalized);
        } catch (MalformedURLException e) {
            throw new IOException("Enter a valid Fred Server URL", e);
        }
        String protocol = parsed.getProtocol();
        String host = parsed.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
        if (!"https".equalsIgnoreCase(protocol)
                && !(allowDevelopmentLoopback
                        && "http".equalsIgnoreCase(protocol)
                        && loopback)) {
            throw new IOException("Fred Server must use HTTPS");
        }
        if (host == null || host.trim().isEmpty() || parsed.getUserInfo() != null
                || parsed.getQuery() != null || parsed.getRef() != null) {
            throw new IOException("Enter a valid Fred Server URL without credentials, a query, or a fragment");
        }
        return normalized;
    }
}
