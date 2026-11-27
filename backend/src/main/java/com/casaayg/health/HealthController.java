package com.casaayg.health;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.casaayg.health.HealthService.Health;

@RestController
class HealthController {

    private final HealthService health;

    HealthController(HealthService health) {
        this.health = health;
    }

    /** «¿Voy bien?» del espacio, a hoy. */
    @GetMapping("/api/spaces/{spaceId}/health")
    Health health(@PathVariable UUID spaceId) {
        return health.health(spaceId);
    }
}
