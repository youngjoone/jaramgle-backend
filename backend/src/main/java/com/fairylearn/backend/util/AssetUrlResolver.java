package com.fairylearn.backend.util;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class AssetUrlResolver {

    private static final String CHARACTER_IMAGE_DIR =
            System.getenv().getOrDefault("CHARACTER_IMAGE_DIR", "/Users/kyj/testchardir");
    private static final String IMAGE_BASE_DIR =
            System.getenv().getOrDefault("IMAGE_BASE_DIR", "/Users/kyj/testimagedir");

    private AssetUrlResolver() {}

    public static String toPublicUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("/api/") || trimmed.startsWith("/characters/")
                || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.startsWith("file://")) {
            try {
                Path path = Paths.get(URI.create(trimmed));
                return mapPath(path);
            } catch (Exception ignored) {
                return trimmed;
            }
        }
        if (trimmed.startsWith("/")) {
            try {
                return mapPath(Paths.get(trimmed));
            } catch (Exception ignored) {
                return trimmed;
            }
        }
        return trimmed;
    }

    private static String mapPath(Path path) {
        String normalized = path.toString().replace("\\", "/");
        if (normalized.contains(CHARACTER_IMAGE_DIR)) {
            String relative = normalized.substring(normalized.indexOf(CHARACTER_IMAGE_DIR) + CHARACTER_IMAGE_DIR.length());
            relative = relative.replaceFirst("^/+", "");
            return "/characters/" + relative;
        }
        if (normalized.contains(IMAGE_BASE_DIR)) {
            String relative = normalized.substring(normalized.indexOf(IMAGE_BASE_DIR) + IMAGE_BASE_DIR.length());
            relative = relative.replaceFirst("^/+", "");
            return "/api/image/" + relative;
        }
        return "/api/image/" + path.getFileName().toString();
    }
}
