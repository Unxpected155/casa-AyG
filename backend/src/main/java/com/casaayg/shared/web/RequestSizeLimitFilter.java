package com.casaayg.shared.web;

import java.io.IOException;
import java.util.Set;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Tope al tamaño del cuerpo de las peticiones (OWASP API4: consumo de recursos).
 * Tomcat limita los formularios, pero NO los JSON: sin esto, un cuerpo de cientos de MB
 * se leería entero a memoria. La API nunca necesita más de 64 KB.
 * Se exige Content-Length (los navegadores siempre lo mandan con fetch y un string).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    static final long MAX_BYTES = 64 * 1024;
    private static final Set<String> WITH_BODY = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (WITH_BODY.contains(request.getMethod())) {
            long length = request.getContentLengthLong();
            if (length > MAX_BYTES) {
                response.sendError(HttpStatus.CONTENT_TOO_LARGE.value());
                return;
            }
            if (length < 0 && request.getHeader("Transfer-Encoding") != null) {
                response.sendError(HttpStatus.LENGTH_REQUIRED.value());
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
