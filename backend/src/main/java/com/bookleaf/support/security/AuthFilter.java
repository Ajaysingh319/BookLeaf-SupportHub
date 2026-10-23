package com.bookleaf.support.security;

import com.bookleaf.support.domain.Role;
import com.bookleaf.support.web.error.ErrorBodies;
import io.jsonwebtoken.JwtException;
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
import java.util.List;

/**
 * The single place where API access is decided, server-side:
 * <ul>
 *   <li>/api/v1/auth/login, /api/v1/health and /api/v1/config are public;</li>
 *   <li>every other /api/v1 route needs a valid bearer token;</li>
 *   <li>/api/v1/author/** is for authors only and /api/v1/admin/** for admins only.</li>
 * </ul>
 * Per-record ownership (an author reading someone else's ticket) is enforced again in
 * the services by scoping queries to the caller's id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/v1/";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String HEALTH_PATH = "/api/v1/health";
    private static final String CONFIG_PATH = "/api/v1/config";
    private static final String AUTHOR_PREFIX = "/api/v1/author/";
    private static final String ADMIN_PREFIX = "/api/v1/admin/";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public AuthFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // CORS preflight requests carry no token; Spring's CORS handling answers them.
        return !path.startsWith(API_PREFIX)
                || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || path.equals(LOGIN_PATH)
                || path.equals(HEALTH_PATH)
                || path.equals(CONFIG_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Please sign in to continue.");
            return;
        }

        AuthUser user;
        try {
            user = jwtService.verify(header.substring("Bearer ".length()).trim());
        } catch (JwtException | IllegalArgumentException ex) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Your session is not valid or has expired. Please sign in again.");
            return;
        }

        String path = request.getRequestURI();
        if (path.startsWith(AUTHOR_PREFIX) && user.role() != Role.AUTHOR) {
            reject(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "You do not have access to this resource.");
            return;
        }
        if (path.startsWith(ADMIN_PREFIX) && user.role() != Role.ADMIN) {
            reject(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "You do not have access to this resource.");
            return;
        }

        request.setAttribute(AuthUser.REQUEST_ATTRIBUTE, user);
        chain.doFilter(request, response);
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
