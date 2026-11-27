package com.casaayg.push;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Notificaciones del dispositivo actual. Todo es de la persona con sesión (RLS: solo lo suyo). */
@RestController
@Validated
@RequestMapping("/api/push")
class PushController {

    private final PushService push;
    private final WebPushSender sender;

    PushController(PushService push, WebPushSender sender) {
        this.push = push;
        this.sender = sender;
    }

    /** Lo que el navegador manda al suscribirse (PushSubscription.toJSON()). */
    record SubscriptionRequest(@NotNull @Size(max = 1000) String endpoint, @NotNull @Valid Keys keys,
                               Long expirationTime) {
        record Keys(@NotNull @Size(max = 100) String p256dh, @NotNull @Size(max = 30) String auth) {}
    }

    record EndpointRequest(@NotNull @Size(max = 1000) String endpoint) {}

    record PreferenceRequest(@NotNull Boolean enabled) {}

    @GetMapping
    Map<String, Object> status() {
        return sender.enabled()
            ? Map.of("enabled", true, "publicKey", sender.publicKey(), "devices", push.count())
            : Map.of("enabled", false);
    }

    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void subscribe(@Valid @RequestBody SubscriptionRequest body) {
        push.subscribe(new PushService.Subscription(body.endpoint(), body.keys().p256dh(), body.keys().auth()));
    }

    @DeleteMapping("/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unsubscribe(@Valid @RequestBody EndpointRequest body) {
        push.unsubscribe(body.endpoint());
    }

    /** Qué tipos de aviso quiere la persona (valen en todos sus dispositivos). */
    @GetMapping("/preferences")
    java.util.List<PushService.Preference> preferences() {
        return push.preferences();
    }

    @PutMapping("/preferences/{kind}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setPreference(@PathVariable PushKind kind, @Valid @RequestBody PreferenceRequest body) {
        push.setPreference(kind, body.enabled());
    }

    @PostMapping("/test")
    Map<String, Integer> test() {
        return Map.of("sent", push.test());
    }
}
