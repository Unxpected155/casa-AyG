package com.casaayg.history;

import java.time.YearMonth;
import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@Validated
class HistoryController {

    private final HistoryService history;

    HistoryController(HistoryService history) {
        this.history = history;
    }

    @GetMapping("/api/spaces/{spaceId}/history")
    HistoryService.History history(@PathVariable UUID spaceId,
                                   @RequestParam @Min(2000) @Max(2100) int year,
                                   @RequestParam @Min(1) @Max(12) int month) {
        return history.history(spaceId, YearMonth.of(year, month));
    }
}
