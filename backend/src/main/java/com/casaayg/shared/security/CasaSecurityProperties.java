package com.casaayg.shared.security;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param rpId            dominio al que quedan ligadas las passkeys (en producción, el nombre
 *                        *.ts.net de Tailscale). Cambiarlo obliga a registrar las passkeys de nuevo.
 * @param origins         orígenes exactos desde los que se acepta una ceremonia WebAuthn.
 * @param sessionAbsolute duración máxima de una sesión de passkey, aunque se use.
 * @param sessionCode     duración máxima de una sesión abierta con código.
 * @param forceHsts       enviar HSTS siempre. En producción el TLS lo termina Tailscale (proxy local),
 *                        así que Spring ve HTTP y no lo enviaría por su cuenta.
 */
@ConfigurationProperties("casaayg.security")
public record CasaSecurityProperties(String rpId, Set<String> origins, Duration sessionAbsolute, Duration sessionCode,
                                     boolean forceHsts) {}
