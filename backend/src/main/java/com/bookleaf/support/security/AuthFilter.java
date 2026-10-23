package com.bookleaf.support.security;

import com.bookleaf.support.config.AppProperties;
import com.bookleaf.support.domain.Role;
import com.bookleaf.support.web.error.ErrorBodies;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The single place where API access is decided, server-side:
 * <ul>
 *   <li>/api/v1/auth/login, /session, /logout, /health and /config are public;</li>
 *   <li>every other /api/v1 route needs a valid session cookie;</li>
 *   <li>/api/v1/author/** is for authors only and /api/v1/admin/** for admins only;</li>
 *   <li>writes (POST, PUT, PATCH, DELETE) must also carry the session's CSRF token in the
 *       X-CSRF-Token header, and any Origin header must be the configured frontend origin.</li>
 * </ul>
 * Per-record ownership (an author reading someone else's ticket) is enforced again in
 * the services by scoping queries to the caller's id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

    public static final String CSRF_HEADER = "X-CSRF-Token";

    private static final String API_PREFIX = "/api/v1/";
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/login", "/api/v1/auth/session", "/api/v1/auth/logout",
            "/api/v1/health", "/api/v1/config");
    private static final String AUTHOR_PREFIX = "/api/v1/author/";
    private static final String ADMIN_PREFIX = "/api/v1/admin/";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final SessionService sessions;
    private final ObjectMapper objectMapper;
    private final String allowedOrigin;

    public AuthFilter(SessionService sessions, ObjectMapper objectMapper, AppProperties properties) {
        this.sessions = sessions;
        this.objectMapper = objectMapper;
        this.allowedOrigin = properties.cors().allowedOrigin();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // CORS preflight requests carry no cookie; Spring's CORS handling answers them.
        return !path.startsWith(API_PREFIX)
                || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || PUBLIC_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Optional<SessionClaims> session = sessions.resolve(request);
        if (session.isEmpty()) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Please sign in to continue.");
            return;
        }
        SessionClaims claims = session.get();

        String path = request.getRequestURI();
        if (path.startsWith(AUTHOR_PREFIX) && claims.role() != Role.AUTHOR) {
            reject(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have access to this resource.");
            return;
        }
        if (path.startsWith(ADMIN_PREFIX) && claims.role() != Role.ADMIN) {
            reject(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have access to this resource.");
            return;
        }
        if (!SAFE_METHODS.contains(request.getMethod().toUpperCase()) && !passesCsrfChecks(request, claims)) {
            reject(request, response, HttpStatus.FORBIDDEN, "CSRF_INVALID",
                    "Your session could not be verified for this action. Please reload the page and try again.");
            return;
        }

        sessions.renewIfNeeded(claims, response);
        request.setAttribute(AuthUser.REQUEST_ATTRIBUTE, new AuthUser(claims.userId(), claims.role()));
        chain.doFilter(request, response);
    }

    /**
     * Cross-site request forgery defence for cookie sessions: the browser sends the cookie automatically,
     * but only this app's pages can read the CSRF token and put it in a header, and a request that names
     * another site as its Origin is refused.
     */
    private boolean passesCsrfChecks(HttpServletRequest request, SessionClaims claims) {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.equals(allowedOrigin)) {
            return false;
        }
        String sent = request.getHeader(CSRF_HEADER);
        if (sent == null || sent.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                sent.getBytes(StandardCharsets.UTF_8), claims.csrfToken().getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                        String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ErrorBodies.build(code, message, List.of(), ErrorBodies.requestId(request))));
    }
}
