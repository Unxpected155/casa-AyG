package com.casaayg.household;

import java.util.List;
import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Quién cambió qué en la casa (solo lectura). */
@RestController
@Validated
class ChangeLogController {

    private final ChangeLog changes;

    ChangeLogController(ChangeLog changes) {
        this.changes = changes;
    }

    @GetMapping("/api/spaces/{spaceId}/changes")
    List<ChangeLog.Change> recent(@PathVariable UUID spaceId,
                                  @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit) {
        return changes.recent(spaceId, limit);
    }
}
