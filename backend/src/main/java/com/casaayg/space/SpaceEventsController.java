package com.casaayg.space;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.casaayg.shared.security.CurrentUser;

import jakarta.servlet.http.HttpServletResponse;

/** Avisos en vivo de un espacio («cambió algo»): ver {@link SpaceEvents}. */
@RestController
class SpaceEventsController {

    private final Spaces spaces;
    private final SpaceEvents events;

    SpaceEventsController(Spaces spaces, SpaceEvents events) {
        this.spaces = spaces;
        this.events = events;
    }

    @GetMapping(path = "/api/spaces/{spaceId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Transactional(readOnly = true)
    public SseEmitter events(@PathVariable UUID spaceId, HttpServletResponse response) {
        // La membresía se comprueba al abrir; el flujo se cierra solo a los 10 minutos y al
        // reabrirse se vuelve a comprobar.
        spaces.requireMember(spaceId);
        response.setHeader("Cache-Control", "no-store");
        // Que ningún proxy guarde los avisos para mandarlos juntos.
        response.setHeader("X-Accel-Buffering", "no");
        return events.subscribe(spaceId, CurrentUser.require());
    }
}
