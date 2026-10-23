package com.bookleaf.support.service;

import java.util.Map;
import java.util.Optional;

/**
 * Works out what a file really is from its first bytes. The name and the Content-Type header that come with an
 * upload are chosen by the sender, so they are never trusted: a script renamed to photo.png is not a PNG.
 */
public final class FileTypes {

    /** The file extension that goes with each detectable type. */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "application/pdf", "pdf");

    private FileTypes() {
    }

    public static Optional<String> detect(byte[] bytes) {
        if (startsWith(bytes, 0, 0xFF, 0xD8, 0xFF)) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(bytes, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of("image/png");
        }
        if (startsWith(bytes, 0, 'R', 'I', 'F', 'F') && startsWith(bytes, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of("image/webp");
        }
        if (startsWith(bytes, 0, '%', 'P', 'D', 'F', '-')) {
            return Optional.of("application/pdf");
        }
        return Optional.empty();
    }

    /** The extension for a detected type, or an empty string for a type this class does not know. */
    public static String extensionFor(String contentType) {
        return EXTENSIONS.getOrDefault(contentType, "");
    }

    private static boolean startsWith(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
