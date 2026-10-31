package com.migrationagent.config;

import com.migrationagent.model.MigrationSettings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * This service clones repositories, runs builds and pushes branches, so /api must not be open to the world.
 * When {@code migration.api-token} is set, callers must send it as the X-API-Token header, or as ?token=
 * (EventSource in browsers cannot set headers). With no token configured the app should only be bound to localhost.
 */
@Component
public class ApiTokenFilter extends OncePerRequestFilter {

    private final MigrationSettings settings;

    public ApiTokenFilter(MigrationSettings settings) {
        this.settings = settings;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String expected = settings.getApiToken();
        if (expected == null || expected.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader("X-API-Token");
        if (provided == null) {
            provided = request.getParameter("token");
        }
        boolean ok = provided != null && MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
        if (ok) {
            chain.doFilter(request, response);
        } else {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid API token");
        }
    }
}
