package com.casaayg.budget;

import java.util.List;
import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.casaayg.budget.BudgetService.Line;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@Validated
@RequestMapping("/api/spaces/{spaceId}/budgets/{year}/{month}")
class BudgetController {

    private final BudgetService budgets;

    BudgetController(BudgetService budgets) {
        this.budgets = budgets;
    }

    record BudgetRequest(@NotNull @Size(max = BudgetService.MAX_LINES) List<@Valid @NotNull LineRequest> lines) {}

    record LineRequest(@NotNull UUID categoryId, @NotNull java.math.BigDecimal plannedAmount) {}

    @GetMapping
    List<Line> get(@PathVariable UUID spaceId, @PathVariable @Min(2000) @Max(2100) int year,
                   @PathVariable @Min(1) @Max(12) int month) {
        return budgets.get(spaceId, year, month);
    }

    @PutMapping
    List<Line> replace(@PathVariable UUID spaceId, @PathVariable @Min(2000) @Max(2100) int year,
                       @PathVariable @Min(1) @Max(12) int month, @Valid @RequestBody BudgetRequest body) {
        return budgets.replace(spaceId, year, month,
            body.lines().stream().map(l -> new Line(l.categoryId(), l.plannedAmount())).toList());
    }
}
