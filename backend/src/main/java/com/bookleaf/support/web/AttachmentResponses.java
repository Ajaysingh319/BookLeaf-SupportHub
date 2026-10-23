package com.bookleaf.support.web;

import com.bookleaf.support.service.AttachmentService.Download;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/**
 * How a stored file is handed to the browser: always as a download, with the type the server detected, and
 * with headers that stop the browser from running or sniffing it as something else.
 */
final class AttachmentResponses {

    private AttachmentResponses() {
    }

    static ResponseEntity<byte[]> download(Download file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(file.content());
    }
}
