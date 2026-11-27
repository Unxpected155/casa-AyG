package com.casaayg.goal;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** El planeador de metas: «¿Cuándo llego?» y, con un plan guardado, «¿Voy como planeé?». */
@RestController
@RequestMapping("/api/spaces/{spaceId}/planner")
class PlannerController {

    private final PlannerService planner;

    PlannerController(PlannerService planner) {
        this.planner = planner;
    }

    record PlanRequest(@NotNull BigDecimal monthly) {}

    /** {@code monthly}: lo que piensan apartar al mes; sin él, la recomendación. */
    @GetMapping
    PlannerService.Planner planner(@PathVariable UUID spaceId, @RequestParam(required = false) BigDecimal monthly) {
        return planner.planner(spaceId, monthly);
    }

    @GetMapping("/plan")
    ResponseEntity<PlannerService.Progress> progress(@PathVariable UUID spaceId) {
        PlannerService.Progress p = planner.progress(spaceId);
        return p == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(p);
    }

    @PostMapping("/plan")
    PlannerService.Progress save(@PathVariable UUID spaceId, @Valid @RequestBody PlanRequest body) {
        return planner.save(spaceId, body.monthly());
    }

    @DeleteMapping("/plan")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID spaceId) {
        planner.delete(spaceId);
    }
}
