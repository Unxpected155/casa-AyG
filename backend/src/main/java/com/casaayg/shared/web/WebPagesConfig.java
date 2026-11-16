package com.casaayg.shared.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Páginas mínimas para usar las passkeys hasta que llegue el frontend (Fase 5).
 * Son HTML estático sin scripts inline (compatibles con la CSP estricta).
 */
@Configuration
class WebPagesConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/login").setViewName("forward:/login.html");
        registry.addViewController("/webauthn/register").setViewName("forward:/register.html");
    }
}
