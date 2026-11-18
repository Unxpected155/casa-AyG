package com.casaayg.recurring;

import java.math.BigDecimal;
import java.time.YearMonth;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.casaayg.domain.Money;
import com.casaayg.recurring.RecurringService.Data;
import com.casaayg.recurring.RecurringService.Name;
import com.casaayg.recurring.RecurringService.RecurringView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Pagos fijos de un espacio. El DTO no trae id, espacio ni autor (mass assignment). */
@RestController
@Validated
@RequestMapping("/api/spaces/{spaceId}/recurring")
class RecurringController {

    private final RecurringService recurring;

    RecurringController(RecurringService recurring) {
        this.recurring = recurring;
    }

    record RecurringRequest(@NotNull String name, @NotNull UUID categoryId, UUID placeId,
                            @Min(1) @Max(31) Integer dueDay, BigDecimal expectedAmount,
                            @NotNull Money.Currency currency, int notifyDaysBefore, Boolean active) {
        Data toData() {
            if (notifyDaysBefore != 0 && notifyDaysBefore != 1 && notifyDaysBefore != 3) {
                throw new IllegalArgumentException("aviso: 0, 1 o 3 días antes");
            }
            return new Data(new Name(name), categoryId, placeId, dueDay,
                expectedAmount == null ? null : new Money(expectedAmount, currency), currency, notifyDaysBefore,
                !Boolean.FALSE.equals(active));
        }
    }

    @GetMapping
    List<RecurringView> list(@PathVariable UUID spaceId,
                             @RequestParam @Min(2000) @Max(2100) int year,
                             @RequestParam @Min(1) @Max(12) int month) {
        return recurring.list(spaceId, YearMonth.of(year, month));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RecurringView create(@PathVariable UUID spaceId, @Valid @RequestBody RecurringRequest body) {
        return recurring.create(spaceId, body.toData());
    }

    @PutMapping("/{id}")
    RecurringView update(@PathVariable UUID spaceId, @PathVariable UUID id, @Valid @RequestBody RecurringRequest body) {
        return recurring.update(spaceId, id, body.toData());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID spaceId, @PathVariable UUID id) {
        recurring.delete(spaceId, id);
    }
}
