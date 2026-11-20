package com.sanvi.jobscheduler.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates requests carrying a valid {@code X-API-Key}. Keys are compared as SHA-256 digests with
 * {@link MessageDigest#isEqual} (constant time). The principal is a short fingerprint of the key, never
 * the key itself, so it is safe to log and to use in rate-limit bucket names.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-API-Key";

    private final List<byte[]> keyDigests;
    private final AuthenticationEntryPoint entryPoint;

    public ApiKeyAuthenticationFilter(List<String> keys, AuthenticationEntryPoint entryPoint) {
        this.keyDigests = keys.stream().map(ApiKeyAuthenticationFilter::sha256).toList();
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented == null || presented.isEmpty()) {
            chain.doFilter(request, response); // anonymous; protected paths are rejected by authorization
            return;
        }
        byte[] digest = sha256(presented);
        boolean valid = false;
        for (byte[] candidate : keyDigests) {
            valid |= MessageDigest.isEqual(candidate, digest);
        }
        if (!valid) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new BadCredentialsException("Invalid API key"));
            return;
        }
        var authentication = new PreAuthenticatedAuthenticationToken(fingerprint(digest), null,
            AuthorityUtils.createAuthorityList("ROLE_API_CLIENT"));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    static String fingerprint(byte[] digest) {
        return "key-" + HexFormat.of().formatHex(digest, 0, 6);
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
