package com.casaayg.shared.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** POST /login/code con los parámetros "username" y "code" (formulario). */
public class CodeLoginFilter extends AbstractAuthenticationProcessingFilter {

    public CodeLoginFilter(AuthenticationManager manager) {
        super(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/login/code"), manager);
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException {
        String username = request.getParameter("username");
        String code = request.getParameter("code");
        return getAuthenticationManager().authenticate(new CodeAuthentication.Token(
            username == null ? "" : username.strip(), code, request.getRemoteAddr()));
    }
}
