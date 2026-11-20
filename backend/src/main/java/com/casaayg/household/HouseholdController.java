package com.casaayg.household;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.casaayg.household.HouseholdService.ClosePreview;
import com.casaayg.household.HouseholdService.Household;
import com.casaayg.household.HouseholdService.Settings;
import com.casaayg.shared.security.RecentAuthentication;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** La casa (espacio familiar): saldos de la pareja, aportes y cierre del mes. */
@RestController
@Validated
class HouseholdController {

    private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);

    private final HouseholdService household;

    HouseholdController(HouseholdService household) {
        this.household = household;
    }

    record SettingsRequest(@NotNull BigDecimal personalFloor, @NotNull BigDecimal bonusPct) {}

    record DestinationRequest(UUID placeId) {}

    record ContributionRequest(@NotNull UUID fromPlace, @NotNull UUID toPlace, @NotNull BigDecimal amount,
                               @NotNull LocalDate date) {}

    record CloseRequest(@Min(2000) @Max(2100) int year, @Min(1) @Max(12) int month, @NotNull BigDecimal bonusPct,
                        @NotNull UUID fromPlace, UUID restPlace) {}

    @GetMapping("/api/spaces/{spaceId}/household")
    Household get(@PathVariable UUID spaceId) {
        return household.household(spaceId);
    }

    @PutMapping("/api/spaces/{spaceId}/household/settings")
    Settings settings(@PathVariable UUID spaceId, @Valid @RequestBody SettingsRequest body) {
        return household.updateSettings(spaceId, body.personalFloor(), body.bonusPct());
    }

    @PutMapping("/api/me/personal-destination")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void destination(@Valid @RequestBody DestinationRequest body) {
        household.setDestination(body.placeId());
    }

    @PostMapping("/api/spaces/{spaceId}/household/contributions")
    @ResponseStatus(HttpStatus.CREATED)
    void contribute(@PathVariable UUID spaceId, @Valid @RequestBody ContributionRequest body) {
        if (body.date().isBefore(MIN_DATE) || body.date().isAfter(LocalDate.now().plusYears(1))) {
            throw new IllegalArgumentException("fecha fuera de rango");
        }
        household.contribute(spaceId, body.fromPlace(), body.toPlace(), body.amount(), body.date());
    }

    @GetMapping("/api/spaces/{spaceId}/household/close")
    ClosePreview preview(@PathVariable UUID spaceId,
                         @RequestParam @Min(2000) @Max(2100) int year,
                         @RequestParam @Min(1) @Max(12) int month,
                         @RequestParam(required = false) BigDecimal bonusPct) {
        return household.preview(spaceId, YearMonth.of(year, month), bonusPct);
    }

    /** Mueve dinero a los espacios personales: pide passkey reciente, como la fusión. */
    @PostMapping("/api/spaces/{spaceId}/household/close")
    ClosePreview close(@PathVariable UUID spaceId, @Valid @RequestBody CloseRequest body) {
        RecentAuthentication.require();
        return household.close(spaceId, YearMonth.of(body.year(), body.month()), body.bonusPct(), body.fromPlace(),
            body.restPlace());
    }

    /** Reabrir el último mes cerrado: deshace sus pagos. Passkey reciente, como cerrar. */
    @DeleteMapping("/api/spaces/{spaceId}/household/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reopen(@PathVariable UUID spaceId,
                @RequestParam @Min(2000) @Max(2100) int year,
                @RequestParam @Min(1) @Max(12) int month) {
        RecentAuthentication.require();
        household.reopen(spaceId, YearMonth.of(year, month));
    }
}
