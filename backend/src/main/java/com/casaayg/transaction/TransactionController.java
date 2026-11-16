package com.casaayg.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
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
import com.casaayg.domain.Text;
import com.casaayg.domain.Text.Description;
import com.casaayg.transaction.TransactionService.Page;
import com.casaayg.transaction.TransactionService.TransactionData;
import com.casaayg.transaction.TransactionService.TransactionView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@RestController
@Validated
@RequestMapping("/api/spaces/{spaceId}/transactions")
class TransactionController {

    private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);

    private final TransactionService transactions;

    TransactionController(TransactionService transactions) {
        this.transactions = transactions;
    }

    /**
     * DTO de entrada (mass assignment): NO tiene spaceId, createdBy, source ni id.
     * Un JSON con esos campos se rechaza con 400 (fail-on-unknown-properties).
     */
    record TransactionRequest(@NotNull UUID categoryId, @NotNull LocalDate date, @NotNull String description,
                              @NotNull BigDecimal amount, @NotNull Money.Currency currency, String paymentMethod) {

        TransactionData toData() {
            if (date.isBefore(MIN_DATE) || date.isAfter(LocalDate.now().plusYears(1))) {
                throw new IllegalArgumentException("fecha fuera de rango");
            }
            return new TransactionData(categoryId, date, new Description(description), new Money(amount, currency),
                Text.optionalPaymentMethod(paymentMethod));
        }
    }

    @GetMapping
    Page list(@PathVariable UUID spaceId,
              @RequestParam @Min(2000) @Max(2100) int year,
              @RequestParam @Min(1) @Max(12) int month,
              @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
              @RequestParam(defaultValue = "50") @Min(1) @Max(TransactionService.MAX_PAGE_SIZE) int size) {
        return transactions.list(spaceId, YearMonth.of(year, month), page, size);
    }

    @GetMapping("/{id}")
    TransactionView get(@PathVariable UUID spaceId, @PathVariable UUID id) {
        return transactions.get(spaceId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TransactionView create(@PathVariable UUID spaceId, @Valid @RequestBody TransactionRequest body) {
        return transactions.create(spaceId, body.toData());
    }

    @PutMapping("/{id}")
    TransactionView update(@PathVariable UUID spaceId, @PathVariable UUID id, @Valid @RequestBody TransactionRequest body) {
        return transactions.update(spaceId, id, body.toData());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID spaceId, @PathVariable UUID id) {
        transactions.delete(spaceId, id);
    }
}
