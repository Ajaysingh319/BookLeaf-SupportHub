package com.bookleaf.support.web.error;

import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the common error shape: { error: { code, message, details? }, requestId }. */
public final class ErrorBodies {

    public static final String REQUEST_ID_ATTRIBUTE = "requestId";

    private ErrorBodies() {
    }

    public static Map<String, Object> build(String code, String message,
                                            List<ApiException.FieldDetail> details, String requestId) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (details != null && !details.isEmpty()) {
            error.put("details", details);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("requestId", requestId);
        return body;
    }

    public static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return value == null ? null : value.toString();
    }
}
