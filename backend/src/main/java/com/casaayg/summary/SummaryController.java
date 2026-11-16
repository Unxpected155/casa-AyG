package com.casaayg.summary;

import java.time.YearMonth;
import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.casaayg.exchange.ExchangeRateService;
import com.casaayg.exchange.ExchangeRateService.Rate;
import com.casaayg.summary.SummaryService.Summary;
import com.casaayg.shared.web.NotFoundException;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@Validated
class SummaryController {

    private final SummaryService summaries;
    private final ExchangeRateService rates;

    SummaryController(SummaryService summaries, ExchangeRateService rates) {
        this.summaries = summaries;
        this.rates = rates;
    }

    @GetMapping("/api/spaces/{spaceId}/summary")
    Summary month(@PathVariable UUID spaceId, @RequestParam @Min(2000) @Max(2100) int year,
                  @RequestParam @Min(1) @Max(12) int month) {
        return summaries.month(spaceId, YearMonth.of(year, month));
    }

    @GetMapping("/api/exchange-rate")
    Rate latestRate() {
        return rates.latest().orElseThrow(NotFoundException::new);
    }
}
