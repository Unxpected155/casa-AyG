package com.casaayg.invoice;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.casaayg.audit.AuditService;
import com.casaayg.shared.security.CurrentUser;
import com.casaayg.shared.web.ConflictException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Mi cédula para las facturas: ponerla, saber si está y quitarla. Nunca se devuelve. */
@RestController
@RequestMapping("/api/me/tax-id")
class TaxIdController {

    private final TaxIdService taxIds;
    private final AuditService audit;

    TaxIdController(TaxIdService taxIds, AuditService audit) {
        this.taxIds = taxIds;
        this.audit = audit;
    }

    record TaxIdRequest(@NotBlank @Size(max = 30) String number) {}

    @GetMapping
    @Transactional(readOnly = true)
    Map<String, Boolean> status() {
        CurrentUser.require();
        return Map.of("set", taxIds.configured() && taxIds.mineIsSet(), "available", taxIds.configured());
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void set(@Valid @RequestBody TaxIdRequest body, HttpServletRequest request) {
        UUID me = CurrentUser.require();
        if (TaxIdService.normalize(body.number()).isEmpty()) {
            throw new ConflictException("Revisa la cédula: son 9 dígitos (o los 11 o 12 del DIMEX).");
        }
        taxIds.setMine(body.number());
        audit.record(me, "CEDULA_GUARDADA", request);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void clear(HttpServletRequest request) {
        UUID me = CurrentUser.require();
        taxIds.clearMine();
        audit.record(me, "CEDULA_QUITADA", request);
    }
}
