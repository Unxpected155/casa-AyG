package com.casaayg.goal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.casaayg.domain.Money;
import com.casaayg.domain.Text;
import com.casaayg.goal.GoalService.Data;
import com.casaayg.goal.GoalService.GoalView;
import com.casaayg.goal.GoalService.Kind;
import com.casaayg.goal.GoalService.Part;
import com.casaayg.goal.GoalService.Status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Metas de un espacio. Los DTO no traen id, espacio, autor, avance ni hitos (mass assignment). */
@RestController
@Validated
@RequestMapping("/api/spaces/{spaceId}/goals")
class GoalController {

    private final GoalService goals;

    GoalController(GoalService goals) {
        this.goals = goals;
    }

    record PartRequest(@NotNull String name, @NotNull BigDecimal amount) {}

    record GoalRequest(@NotNull String name, @NotNull Kind kind, BigDecimal targetAmount, Integer emergencyMonths,
                       @NotNull Money.Currency currency, LocalDate targetDate,
                       @Size(max = 20) List<@NotNull UUID> placeIds,
                       @Size(max = GoalService.MAX_PARTS) List<@Valid @NotNull PartRequest> parts) {
        Data toData() {
            if (targetDate != null && (targetDate.isBefore(LocalDate.of(2000, 1, 1)) || targetDate.isAfter(LocalDate.now().plusYears(60)))) {
                throw new IllegalArgumentException("fecha fuera de rango");
            }
            return new Data(name(name), kind, targetAmount, emergencyMonths, currency, targetDate,
                placeIds == null ? List.of() : placeIds,
                parts == null ? List.of() : parts.stream().map(p -> new Part(name(p.name()), p.amount())).toList());
        }

        private static String name(String raw) {
            String value = Text.optionalNote(raw);
            if (value == null || value.length() > 60) {
                throw new IllegalArgumentException("nombre inválido");
            }
            return value;
        }
    }

    record StatusRequest(@NotNull Status status) {}

    record OrderRequest(@NotNull @Size(max = 100) List<@NotNull UUID> ids) {}

    @GetMapping
    List<GoalView> list(@PathVariable UUID spaceId) {
        return goals.list(spaceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    GoalView create(@PathVariable UUID spaceId, @Valid @RequestBody GoalRequest body) {
        return goals.create(spaceId, body.toData());
    }

    @PutMapping("/{id}")
    GoalView update(@PathVariable UUID spaceId, @PathVariable UUID id, @Valid @RequestBody GoalRequest body) {
        return goals.update(spaceId, id, body.toData());
    }

    @PutMapping("/{id}/status")
    GoalView status(@PathVariable UUID spaceId, @PathVariable UUID id, @Valid @RequestBody StatusRequest body) {
        return goals.setStatus(spaceId, id, body.status());
    }

    @PutMapping("/order")
    List<GoalView> reorder(@PathVariable UUID spaceId, @Valid @RequestBody OrderRequest body) {
        return goals.reorder(spaceId, body.ids());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID spaceId, @PathVariable UUID id) {
        goals.delete(spaceId, id);
    }
}
