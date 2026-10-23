package com.bookleaf.support.dto;

import java.time.Instant;
import java.util.UUID;

/** What the API says about an attachment; the bytes are only served by the download endpoints. */
public record AttachmentInfo(UUID id, String fileName, String contentType, long sizeBytes, Instant createdAt) {
}
