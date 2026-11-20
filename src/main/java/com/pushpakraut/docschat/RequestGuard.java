package com.pushpakraut.docschat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 1. Host check: only answers requests addressed to its own host names (blocks DNS rebinding).
 * 2. Origin check: rejects writes sent from other websites (blocks cross-site uploads).
 * 3. Rate limits per IP: uploads and questions, so one script cannot fill the demo or burn the AI quota.
 * 4. Visitor id: gives each browser a private, random cookie so visitors never see each other's documents.
 */
@Component
public class RequestGuard extends OncePerRequestFilter {
    static final String VISITOR = "visitor";
    private final Set<String> allowedHosts;
    private final RateLimiter uploads;
    private final RateLimiter questions;

    public RequestGuard(@Value("${app.allowed-hosts}") String hosts,
                        @Value("${app.limits.uploads-per-ip:5}") int uploadsPerIp,
                        @Value("${app.limits.questions-per-ip:30}") int questionsPerIp,
                        @Value("${app.limits.rate-window-minutes:10}") long windowMinutes) {
        this.uploads = new RateLimiter(uploadsPerIp, windowMinutes * 60_000);
        this.questions = new RateLimiter(questionsPerIp, windowMinutes * 60_000);
        this.allowedHosts = Arrays.stream(hosts.split(",")).map(String::trim).map(String::toLowerCase)
                .filter(h -> !h.isEmpty()).collect(Collectors.toSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!allowedHosts.contains(req.getServerName().toLowerCase())) {
            res.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown host");
            return;
        }
        String origin = req.getHeader("Origin");
        if (origin != null && !"GET".equals(req.getMethod()) && !isAllowedOrigin(origin)) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN, "Cross-site request blocked");
            return;
        }
        if ("POST".equals(req.getMethod())) {
            // Behind the host's proxy, server.forward-headers-strategy makes this the real client IP
            String ip = req.getRemoteAddr();
            RateLimiter limiter = req.getRequestURI().startsWith("/api/documents") ? uploads : questions;
            if (!limiter.tryAcquire(ip)) {
                res.setHeader("Retry-After", "600");
                res.sendError(429, "Too many requests. Please wait a few minutes.");
                return;
            }
        }
        String visitor = null;
        if (req.getCookies() != null) {
            for (Cookie c : req.getCookies()) {
                if (VISITOR.equals(c.getName()) && c.getValue().matches("[0-9a-f-]{36}")) visitor = c.getValue();
            }
        }
        if (visitor == null) {
            visitor = UUID.randomUUID().toString();
            res.addHeader("Set-Cookie", VISITOR + "=" + visitor + "; Path=/; HttpOnly; SameSite=Strict"
                    + (req.isSecure() ? "; Secure" : ""));
        }
        req.setAttribute(VISITOR, visitor);
        chain.doFilter(req, res);
    }

    private boolean isAllowedOrigin(String origin) {
        try {
            String host = URI.create(origin).getHost();
            return host != null && allowedHosts.contains(host.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
