package com.jaramgle.backend.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class OfficialCharacterReferenceResolver {

    private static final String REFERENCE_DIR = System.getenv()
            .getOrDefault("OFFICIAL_CHARACTER_REFERENCE_DIR", "data/character-reference-sheets");

    private static final Map<String, String> REFERENCE_FILES = Map.of(
            "busan-boogi", "busan-boogi-2d-sheet-approved.png",
            "daegu-dodalsu", "daegu-dodalsu-2d-sheet-approved.png",
            "dodalssu", "daegu-dodalsu-2d-sheet-approved.png",
            "dodalsu", "daegu-dodalsu-2d-sheet-approved.png",
            "chungbuk-godeumi-bareumi", "chungbuk-godeumi-bareumi-2d-sheet-approved.png",
            "godeumi-bareumi", "chungbuk-godeumi-bareumi-2d-sheet-approved.png");

    private OfficialCharacterReferenceResolver() {
    }

    public static Optional<String> resolveReferenceImageUrl(String slug) {
        String normalizedSlug = normalizeSlug(slug);
        if (normalizedSlug.isBlank()) {
            return Optional.empty();
        }

        String fileName = REFERENCE_FILES.get(normalizedSlug);
        if (fileName == null) {
            return Optional.empty();
        }

        Path resolvedPath = Paths.get(REFERENCE_DIR).resolve(fileName).toAbsolutePath().normalize();
        if (!Files.isRegularFile(resolvedPath)) {
            return Optional.empty();
        }

        String unixPath = resolvedPath.toString().replace("\\", "/");
        if (!unixPath.startsWith("/")) {
            unixPath = "/" + unixPath;
        }
        return Optional.of("file://" + unixPath);
    }

    private static String normalizeSlug(String slug) {
        return slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
    }
}
