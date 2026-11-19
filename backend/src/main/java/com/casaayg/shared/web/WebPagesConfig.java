package com.casaayg.shared.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * El frontend (React, Fase 5) es una SPA: sus rutas se sirven con el mismo index.html y el
 * enrutamiento lo hace el navegador. La lista es explícita (lista blanca), igual que la seguridad.
 */
@Configuration
public class WebPagesConfig implements WebMvcConfigurer {

    /** Rutas de la SPA (ver frontend/src/router.ts). */
    public static final String[] SPA_ROUTES = {"/", "/login", "/registrar", "/movimientos", "/plan", "/cuenta", "/boda", "/lugares", "/metas", "/historia", "/casa"};

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String route : SPA_ROUTES) {
            registry.addViewController(route).setViewName("forward:/index.html");
        }
    }
}
