package com.casaayg.place;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import com.casaayg.domain.Text;
import com.casaayg.domain.Text.PlaceName;
import com.casaayg.place.PlaceService.Kind;
import com.casaayg.place.PlaceService.NewPlace;
import com.casaayg.place.PlaceService.NewTransfer;
import com.casaayg.place.PlaceService.PlaceChanges;
import com.casaayg.place.PlaceService.PlaceView;
import com.casaayg.place.PlaceService.TransferView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Lugares y dinero movido. Los DTO de entrada NO tienen spaceId, createdBy, id ni saldo
 * (mass assignment): un JSON con campos de más se rechaza con 400.
 */
@RestController
@Validated
@RequestMapping("/api/spaces/{spaceId}")
class PlaceController {

    private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);

    private final PlaceService places;
    private final CdpService cdps;

    PlaceController(PlaceService places, CdpService cdps) {
        this.places = places;
        this.cdps = cdps;
    }

    static LocalDate checkDate(LocalDate date) {
        if (date.isBefore(MIN_DATE) || date.isAfter(LocalDate.now().plusYears(1))) {
            throw new IllegalArgumentException("fecha fuera de rango");
        }
        return date;
    }

    record PlaceRequest(@NotNull String name, @NotNull Kind kind, @NotNull Money.Currency currency,
                        @NotNull BigDecimal openingBalance, @NotNull LocalDate openingDate, UUID parentId,
                        Boolean savings) {
        NewPlace toNew() {
            return new NewPlace(new PlaceName(name), kind, new Money(openingBalance, currency), checkDate(openingDate),
                parentId, savings);
        }
    }

    record PlaceUpdate(@NotNull String name, @NotNull BigDecimal openingBalance, @NotNull LocalDate openingDate,
                       UUID parentId, boolean archived, Boolean savings) {
        PlaceChanges toChanges() {
            return new PlaceChanges(new PlaceName(name), openingBalance, checkDate(openingDate), parentId, archived,
                savings);
        }
    }

    record TransferRequest(@NotNull UUID from, @NotNull UUID to, @NotNull LocalDate date,
                           @NotNull BigDecimal amount, String description) {
        NewTransfer toNew() {
            return new NewTransfer(from, to, checkDate(date), amount, Text.optionalNote(description));
        }
    }

    record DistributeItem(@NotNull UUID to, @NotNull BigDecimal amount) {}

    /** Datos del CDP como vienen en el comprobante del banco. */
    record CdpRequest(@NotNull @jakarta.validation.constraints.DecimalMin(value = "0", inclusive = false)
                      @jakarta.validation.constraints.DecimalMax(value = "100", inclusive = false) BigDecimal rate,
                      @Min(1) @Max(3660) int termDays, int dayBasis,
                      @NotNull @jakarta.validation.constraints.DecimalMin("0")
                      @jakarta.validation.constraints.DecimalMax(value = "100", inclusive = false) BigDecimal taxPct,
                      @NotNull LocalDate maturesOn, @NotNull UUID payoutPlace, BigDecimal lastContribution) {
        CdpService.CdpSettings toSettings() {
            if (dayBasis != 360 && dayBasis != 365) {
                throw new IllegalArgumentException("base de días 360 o 365");
            }
            if (rate.scale() > 4 || taxPct.scale() > 2) {
                throw new IllegalArgumentException("demasiados decimales");
            }
            return new CdpService.CdpSettings(rate, termDays, dayBasis, taxPct, checkDate(maturesOn), payoutPlace,
                lastContribution);
        }
    }

    record RenewRequest(BigDecimal rate, BigDecimal contribution, Boolean tithe) {}

    record DistributeRequest(@NotNull UUID from, @NotNull LocalDate date, String description,
                             @NotNull @Size(min = 1, max = PlaceService.MAX_BATCH) List<@Valid @NotNull DistributeItem> items) {}

    @GetMapping("/places")
    List<PlaceView> list(@PathVariable UUID spaceId) {
        return places.list(spaceId);
    }

    @PostMapping("/places")
    @ResponseStatus(HttpStatus.CREATED)
    PlaceView create(@PathVariable UUID spaceId, @Valid @RequestBody PlaceRequest body) {
        return places.create(spaceId, body.toNew());
    }

    @PutMapping("/places/{id}")
    PlaceView update(@PathVariable UUID spaceId, @PathVariable UUID id, @Valid @RequestBody PlaceUpdate body) {
        return places.update(spaceId, id, body.toChanges());
    }

    @PutMapping("/places/{id}/cdp")
    PlaceView configureCdp(@PathVariable UUID spaceId, @PathVariable UUID id, @Valid @RequestBody CdpRequest body) {
        return cdps.configure(spaceId, id, body.toSettings());
    }

    @GetMapping("/places/{id}/cdp/preview")
    CdpService.Renewal previewCdp(@PathVariable UUID spaceId, @PathVariable UUID id,
                                  @RequestParam(required = false) BigDecimal rate,
                                  @RequestParam(required = false) BigDecimal contribution) {
        return cdps.preview(spaceId, id, rate, contribution);
    }

    @PostMapping("/places/{id}/cdp/renew")
    CdpService.Renewal renewCdp(@PathVariable UUID spaceId, @PathVariable UUID id, @Valid @RequestBody RenewRequest body) {
        return cdps.renew(spaceId, id, body.rate(), body.contribution(), Boolean.TRUE.equals(body.tithe()));
    }

    @GetMapping("/transfers")
    List<TransferView> transfers(@PathVariable UUID spaceId,
                                 @RequestParam @Min(2000) @Max(2100) int year,
                                 @RequestParam @Min(1) @Max(12) int month) {
        return places.transfers(spaceId, YearMonth.of(year, month));
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    TransferView transfer(@PathVariable UUID spaceId, @Valid @RequestBody TransferRequest body) {
        return places.transfer(spaceId, body.toNew());
    }

    @PostMapping("/transfers/distribute")
    @ResponseStatus(HttpStatus.CREATED)
    List<TransferView> distribute(@PathVariable UUID spaceId, @Valid @RequestBody DistributeRequest body) {
        String note = Text.optionalNote(body.description());
        LocalDate date = checkDate(body.date());
        List<NewTransfer> items = body.items().stream()
            .map(i -> new NewTransfer(body.from(), i.to(), date, i.amount(), note))
            .toList();
        return places.distribute(spaceId, body.from(), date, note, items);
    }

    @DeleteMapping("/transfers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteTransfer(@PathVariable UUID spaceId, @PathVariable UUID id) {
        places.deleteTransfer(spaceId, id);
    }
}
