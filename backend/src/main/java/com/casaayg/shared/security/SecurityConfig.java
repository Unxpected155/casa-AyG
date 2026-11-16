package com.casaayg.shared.security;

import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpMessageConverterAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationFilter;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import com.casaayg.audit.AuditService;
import com.casaayg.shared.db.AsUser;
import com.casaayg.shared.web.WebPagesConfig;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Seguridad web de Casa A&G (Fase 3). Ideas clave:
 * <ul>
 *   <li><b>Cerrado por defecto:</b> lista blanca de rutas; lo demás, denyAll.</li>
 *   <li><b>Solo passkeys:</b> sin contraseñas. Los códigos de inscripción/recuperación abren una
 *       sesión que solo puede registrar passkeys.</li>
 *   <li><b>Sesión:</b> cookie __Host- HttpOnly/Secure/SameSite=Strict, ID rotado en cada login,
 *       timeout de inactividad (30 min) y absoluto (7 días), revocable.</li>
 *   <li><b>CSRF</b> con cookie + header (patrón SPA).</li>
 *   <li><b>Acciones sensibles</b> exigen haber usado la passkey hace menos de 5 minutos.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(CasaSecurityProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CasaSecurityProperties props, JdbcClient jdbc,
            LoginThrottle throttle, SessionRegistry sessionRegistry, SessionRevoker revoker,
            AuditService audit, AsUser asUser) throws Exception {

        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Strict").path("/"));

        // Se aplica en CADA login (passkey o código): nuevo ID de sesión (contra fijación de
        // sesión), registro para poder revocarla y nuevo token CSRF.
        SessionAuthenticationStrategy onLogin = new CompositeSessionAuthenticationStrategy(List.of(
            new ChangeSessionIdAuthenticationStrategy(),
            new RegisterSessionAuthenticationStrategy(sessionRegistry),
            new CsrfAuthenticationStrategy(csrfTokens)));

        AuthenticationEntryPoint unauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);

        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/error").permitAll()
                // Páginas estáticas sin datos (los datos llegan por /api con sesión).
                .requestMatchers(GET, WebPagesConfig.SPA_ROUTES).permitAll()
                .requestMatchers(GET, "/index.html", "/assets/**", "/icons/**", "/manifest.webmanifest",
                    "/favicon.svg").permitAll()
                .requestMatchers(POST, "/login/code", "/webauthn/authenticate/options", "/login/webauthn").permitAll()
                // Registrar una passkey: sesión de código, o passkey usada hace < 5 min.
                .requestMatchers(POST, "/webauthn/register/options", "/webauthn/register").access(enrollOrRecentPasskey())
                // El borrado de Spring permite borrar la ÚLTIMA passkey: se usa el de /api/me.
                .requestMatchers(DELETE, "/webauthn/register/**").denyAll()
                .requestMatchers("/api/**").access(passkeySession())
                .anyRequest().denyAll())
            .webAuthn(webAuthn -> webAuthn
                .rpName("Casa A&G")
                .rpId(props.rpId())
                .allowedOrigins(props.origins())
                .disableDefaultRegistrationPage(true)
                // Spring no rota el ID de sesión tras el login con passkey (estrategia nula por
                // defecto). Se le inyecta la estrategia y los manejadores con auditoría.
                .withObjectPostProcessor(new ObjectPostProcessor<WebAuthnAuthenticationFilter>() {
                    @Override
                    public <O extends WebAuthnAuthenticationFilter> O postProcess(O filter) {
                        filter.setSessionAuthenticationStrategy(onLogin);
                        filter.setAuthenticationSuccessHandler(passkeySuccess(audit, asUser, jdbc));
                        filter.setAuthenticationFailureHandler(passkeyFailure(audit));
                        return filter;
                    }
                }))
            .addFilterBefore(codeLoginFilter(jdbc, throttle, onLogin, revoker, audit), UsernamePasswordAuthenticationFilter.class)
            // Temprano en la cadena: una sesión vencida no debe llegar a NINGÚN filtro de login/registro.
            .addFilterAfter(new SessionTimeoutFilter(SecurityContextHolder.getContextHolderStrategy(),
                props.sessionAbsolute(), props.sessionCode(), Clock.systemUTC()), SecurityContextHolderFilter.class)
            .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens))
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            // Los filtros de registro de Spring corren ANTES de AuthorizationFilter: este los protege.
            .addFilterAfter(new PasskeyRegistrationGuard(SecurityContextHolder.getContextHolderStrategy(),
                Clock.systemUTC()), CsrfFilter.class)
            .sessionManagement(session -> session
                .sessionFixation(fixation -> fixation.changeSessionId())
                .maximumSessions(-1)
                .sessionRegistry(sessionRegistry))
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                .addLogoutHandler((request, response, auth) -> {
                    if (auth != null) {
                        CurrentUser.idOf(auth.getPrincipal()).ifPresent(id -> audit.record(id, "LOGOUT", request));
                    }
                })
                .deleteCookies("__Host-casaayg"))
            .exceptionHandling(errors -> errors
                .defaultAuthenticationEntryPointFor(unauthorized,
                    PathPatternRequestMatcher.withDefaults().matcher("/api/**"))
                .defaultAuthenticationEntryPointFor(unauthorized,
                    PathPatternRequestMatcher.withDefaults().matcher(POST, "/**")))
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                        + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)))
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable());
        return http.build();
    }

    private static CodeLoginFilter codeLoginFilter(JdbcClient jdbc, LoginThrottle throttle,
            SessionAuthenticationStrategy onLogin, SessionRevoker revoker, AuditService audit) {
        CodeLoginFilter filter = new CodeLoginFilter(new ProviderManager(new CodeAuthentication.Provider(jdbc, throttle)));
        filter.setSecurityContextRepository(new HttpSessionSecurityContextRepository());
        filter.setSessionAuthenticationStrategy(onLogin);
        filter.setAuthenticationSuccessHandler((request, response, auth) -> {
            CodePrincipal principal = (CodePrincipal) auth.getPrincipal();
            boolean recovery = CodePrincipal.RECOVERY.equals(principal.purpose());
            if (recovery) {
                // Entrar con código de recuperación = probablemente perdió un dispositivo.
                revoker.revokeOthers(principal.id(), request.getSession().getId());
            }
            audit.record(principal.id(), recovery ? "LOGIN_RECUPERACION" : "LOGIN_INSCRIPCION", request);
            writeJson(response, "{\"redirectUrl\":\"/webauthn/register\"}");
        });
        filter.setAuthenticationFailureHandler((request, response, e) -> {
            audit.record(null, "LOGIN_CODIGO_FALLIDO",
                Map.of("usuario", String.valueOf(request.getParameter("username"))), request);
            response.sendError(HttpStatus.UNAUTHORIZED.value());
        });
        return filter;
    }

    private static AuthenticationSuccessHandler passkeySuccess(AuditService audit, AsUser asUser, JdbcClient jdbc) {
        AuthenticationSuccessHandler delegate = new HttpMessageConverterAuthenticationSuccessHandler();
        return (request, response, auth) -> {
            CurrentUser.idOf(auth.getPrincipal()).ifPresent(id -> {
                asUser.run(id, () -> jdbc.sql("UPDATE app_user SET last_login_at = now() WHERE id = ?")
                    .param(id).update());
                audit.record(id, "LOGIN_OK", request);
            });
            delegate.onAuthenticationSuccess(request, response, auth);
        };
    }

    private static AuthenticationFailureHandler passkeyFailure(AuditService audit) {
        return (request, response, e) -> {
            audit.record(null, "LOGIN_FALLIDO", request);
            response.sendError(HttpStatus.UNAUTHORIZED.value());
        };
    }

    /**
     * La API exige sesión de passkey. Es una regla propia (no hasAuthority) para que una sesión
     * de código reciba 403 y no el redireccionamiento al login del flujo MFA de Spring.
     */
    private static AuthorizationManager<RequestAuthorizationContext> passkeySession() {
        return new AuthorizationManager<>() {
            @Override
            public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                    RequestAuthorizationContext context) {
                Authentication auth = authentication.get();
                return new AuthorizationDecision(auth != null && auth.isAuthenticated()
                    && CurrentUser.hasAuthority(auth, CurrentUser.PASSKEY));
            }
        };
    }

    private static AuthorizationManager<RequestAuthorizationContext> enrollOrRecentPasskey() {
        return new AuthorizationManager<>() {
            @Override
            public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                    RequestAuthorizationContext context) {
                Authentication auth = authentication.get();
                boolean enrolling = auth != null && auth.isAuthenticated()
                    && CurrentUser.hasAuthority(auth, CurrentUser.ENROLL);
                return new AuthorizationDecision(enrolling
                    || RecentAuthentication.isRecentPasskey(auth, Clock.systemUTC()));
            }
        };
    }

    private static void writeJson(HttpServletResponse response, String json) throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType("application/json");
        response.getWriter().write(json);
    }

    /** Fuerza la emisión de la cookie XSRF-TOKEN para que la página pueda leerla (patrón SPA). */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }

    // --- Beans que usa la configuración de WebAuthn de Spring ---

    @Bean
    PublicKeyCredentialUserEntityRepository userEntityRepository(JdbcOperations jdbc) {
        return new JdbcPublicKeyCredentialUserEntityRepository(jdbc);
    }

    @Bean
    UserCredentialRepository userCredentialRepository(JdbcOperations jdbc, AuditService audit) {
        return new AuditingUserCredentialRepository(new JdbcUserCredentialRepository(jdbc), audit);
    }

    @Bean
    UserDetailsService userDetailsService(JdbcClient jdbc) {
        return new CasaUserDetailsService(jdbc);
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** Avisa al SessionRegistry cuando una sesión se destruye (logout o timeout). */
    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }
}
