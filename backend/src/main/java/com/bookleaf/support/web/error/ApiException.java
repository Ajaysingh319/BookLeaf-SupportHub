package com.bookleaf.support.web.error;

import org.springframework.http.HttpStatus;

import java.util.List;

/** An expected failure that maps to a specific HTTP status and error code. */
public class ApiException extends RuntimeException {

    public record FieldDetail(String field, String message) {
    }

    private final HttpStatus status;
    private final String code;
    private final List<FieldDetail> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<FieldDetail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Please correct the highlighted fields.", List.of(new FieldDetail(field, message)));
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public List<FieldDetail> getDetails() { return details; }
}
