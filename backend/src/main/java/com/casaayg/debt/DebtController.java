package com.casaayg.debt;

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

import com.casaayg.debt.DebtService.Concept;
import com.casaayg.debt.DebtService.Creditor;
import com.casaayg.debt.DebtService.Data;
import com.casaayg.debt.DebtService.DebtView;
import com.casaayg.domain.Money;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Deudas de un espacio. El DTO no trae id, espacio, autor ni tipo (mass assignment). */
@RestController
@Validated
@RequestMapping("/api/spaces/{spaceId}/debts")
class DebtController {

    private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);

    private final DebtService debts;

    DebtController(DebtService debts) {
        this.debts = debts;
    }

    record DebtRequest(@NotNull String creditor, @NotNull String concept, @NotNull BigDecimal amount,
                       @NotNull Money.Currency currency, @NotNull LocalDate borrowedOn, LocalDate dueDate,
                       BigDecimal paidBefore, BigDecimal installment, Integer installmentDay) {
        Data toData() {
            LocalDate max = LocalDate.now().plusYears(30);
            if (borrowedOn.isBefore(MIN_DATE) || borrowedOn.isAfter(LocalDate.now().plusDays(1))
                    || (dueDate != null && dueDate.isAfter(max))) {
                throw new IllegalArgumentException("fecha fuera de rango");
            }
            return new Data(new Creditor(creditor), new Concept(concept), new Money(amount, currency), borrowedOn,
                dueDate, paidBefore, installment, installmentDay);
        }
    }

    @GetMapping
    List<DebtView> list(@PathVariable UUID spaceId) {
        return debts.list(spaceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DebtView create(@PathVariable UUID spaceId, @Valid @RequestBody DebtRequest body) {
        return debts.create(spaceId, body.toData());
    }

    @PutMapping("/{id}")
    DebtView update(@PathVariable UUID spaceId, @PathVariable UUID id, @Valid @RequestBody DebtRequest body) {
        return debts.update(spaceId, id, body.toData());
    }

    /** «Me lo perdonó»: salda lo que falta sin gasto. */
    @PostMapping("/{id}/forgive")
    DebtView forgive(@PathVariable UUID spaceId, @PathVariable UUID id) {
        return debts.forgive(spaceId, id);
    }

    @DeleteMapping("/{id}/forgive")
    DebtView unforgive(@PathVariable UUID spaceId, @PathVariable UUID id) {
        return debts.unforgive(spaceId, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID spaceId, @PathVariable UUID id) {
        debts.delete(spaceId, id);
    }
}
