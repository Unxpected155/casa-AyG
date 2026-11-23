package com.casaayg.invoice;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.casaayg.audit.AuditService;
import com.casaayg.shared.security.CurrentUser;
import com.casaayg.shared.security.RecentAuthentication;
import com.casaayg.shared.web.ConflictException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** El buzón de facturas: ver cómo está, conectarlo, desconectarlo y revisarlo ahora. */
@RestController
class MailboxController {

    private final MailboxService mailbox;
    private final MailboxReader reader;
    private final AuditService audit;
    private final AtomicReference<Instant> lastManual = new AtomicReference<>(Instant.EPOCH);
    private final Duration cooldown;

    MailboxController(MailboxService mailbox, MailboxReader reader, AuditService audit,
                      @Value("${casaayg.invoices.check-cooldown:60s}") Duration cooldown) {
        this.mailbox = mailbox;
        this.reader = reader;
        this.audit = audit;
        this.cooldown = cooldown;
    }

    record CallbackRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9/_.\\-]{10,512}") String code,
                           @NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{20,64}") String state) {}

    @GetMapping("/api/invoices/mailbox")
    MailboxService.Status status() {
        CurrentUser.require();
        return mailbox.status();
    }

    /** Conectar pide la passkey reciente: da acceso a leer un buzón. */
    @PostMapping("/api/invoices/mailbox/connect")
    Map<String, String> connect(HttpServletRequest request) {
        CurrentUser.require();
        RecentAuthentication.require();
        return Map.of("url", mailbox.start(request.getSession()));
    }

    @PostMapping("/api/invoices/mailbox/callback")
    Map<String, String> callback(@Valid @RequestBody CallbackRequest body, HttpServletRequest request) {
        UUID me = CurrentUser.require();
        String email = mailbox.finish(request.getSession(), body.code(), body.state(), me);
        audit.record(me, "BUZON_CONECTADO", request);
        return Map.of("email", email);
    }

    @DeleteMapping("/api/invoices/mailbox")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disconnect(HttpServletRequest request) {
        UUID me = CurrentUser.require();
        RecentAuthentication.require();
        mailbox.disconnect();
        audit.record(me, "BUZON_DESCONECTADO", request);
    }

    /** «Revisar ahora»: como mucho una vez por minuto (cada pasada llama a Google). */
    @PostMapping("/api/invoices/mailbox/check")
    MailboxReader.RunResult check() {
        CurrentUser.require();
        Instant now = Instant.now();
        Instant last = lastManual.get();
        if (now.isBefore(last.plus(cooldown)) || !lastManual.compareAndSet(last, now)) {
            throw new ConflictException("Se acaba de revisar. Espera un minuto.");
        }
        return reader.run();
    }

    /**
     * Google vuelve aquí (la dirección registrada en Google Cloud). La cookie de sesión es
     * SameSite=Strict y no viaja en esta navegación desde otro sitio, así que aquí no se hace
     * nada: solo se pasa el código a la pantalla, que lo manda con su sesión (y el state + PKCE
     * guardados en ella hacen que el código no sirva a nadie más).
     */
    @GetMapping("/api/invoices/oauth/callback")
    ResponseEntity<Void> oauthCallback(@RequestParam(required = false) String code,
                                       @RequestParam(required = false) String state) {
        UriComponentsBuilder target = UriComponentsBuilder.fromPath("/facturas");
        if (code != null && state != null && code.matches("[A-Za-z0-9/_.\\-]{10,512}")
                && state.matches("[A-Za-z0-9_\\-]{20,64}")) {
            target.queryParam("code", code).queryParam("state", state);
        } else {
            target.queryParam("buzon", "cancelado");
        }
        URI location = URI.create(target.encode().build().toUriString());
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }
}
