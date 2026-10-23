package com.bookleaf.support.web.error;

import com.bookleaf.support.ai.AiUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import com.bookleaf.support.config.AppProperties;
import com.bookleaf.support.service.AttachmentService;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

/** Turns every failure into the common error shape. Stack traces are only logged. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final long maxFileBytes;

    public GlobalExceptionHandler(AppProperties properties) {
        this.maxFileBytes = properties.attachments().maxFileBytes();
    }

    /** The container refused an oversized upload before it reached the service. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge(HttpServletRequest request) {
        ApiException ex = AttachmentService.fileTooLarge(maxFileBytes);
        return respond(ex.getStatus(), ex.getCode(), ex.getMessage(), List.of(), request);
    }

    /** The request had no file part. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingPart(MissingServletRequestPartException ex,
                                                                 HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Please correct the highlighted fields.",
                List.of(new ApiException.FieldDetail(ex.getRequestPartName(), "Choose a file to attach.")), request);
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<Map<String, Object>> handleTooManyAttempts(TooManyAttemptsException ex,
                                                                     HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(ErrorBodies.build(ex.getCode(), ex.getMessage(), List.of(), ErrorBodies.requestId(request)));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex, HttpServletRequest request) {
        return respond(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getDetails(), request);
    }

    /** The AI could not help. The admin is told to write the reply manually; no provider details leak. */
    @ExceptionHandler(AiUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleAiUnavailable(AiUnavailableException ex,
                                                                   HttpServletRequest request) {
        log.info("AI request failed for request {}: {}", ErrorBodies.requestId(request), ex.reasonCode());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                "AI drafting is unavailable right now. You can write the reply manually.", List.of(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex,
                                                                HttpServletRequest request) {
        List<ApiException.FieldDetail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiException.FieldDetail(error.getField(), error.getDefaultMessage()))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Please correct the highlighted fields.", details, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex,
                                                                HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY",
                "The request body is missing or is not valid JSON.", List.of(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                                  HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Please correct the highlighted fields.",
                List.of(new ApiException.FieldDetail(ex.getName(), "Invalid value.")), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                                      HttpServletRequest request) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "This method is not allowed for this endpoint.", List.of(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex,
                                                                HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "The requested resource was not found.",
                List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex, HttpServletRequest request) {
        String requestId = ErrorBodies.requestId(request);
        log.error("Unexpected error for request {}", requestId, ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Something went wrong. Please try again, and quote the request ID if it keeps happening.",
                List.of(), request);
    }

    private ResponseEntity<Map<String, Object>> respond(HttpStatus status, String code, String message,
                                                        List<ApiException.FieldDetail> details,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ErrorBodies.build(code, message, details, ErrorBodies.requestId(request)));
    }
}
