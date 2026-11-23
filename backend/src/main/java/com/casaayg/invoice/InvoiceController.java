package com.casaayg.invoice;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.casaayg.transaction.TransactionService.TransactionView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Mis facturas por revisar: verlas, confirmarlas en un espacio o descartarlas. */
@RestController
@RequestMapping("/api/invoices")
class InvoiceController {

    private final InvoiceInbox inbox;

    InvoiceController(InvoiceInbox inbox) {
        this.inbox = inbox;
    }

    /** Sin monto, fecha ni moneda: esos salen de la factura firmada y no se cambian. */
    record ConfirmRequest(@NotNull UUID spaceId, @NotNull UUID categoryId, @NotNull UUID placeId,
                          @Size(max = 200) String description) {}

    @GetMapping
    List<InvoiceInbox.Pending> pending() {
        return inbox.pending();
    }

    @PostMapping("/{id}/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    TransactionView confirm(@PathVariable UUID id, @Valid @RequestBody ConfirmRequest body) {
        return inbox.confirm(id, body.spaceId(), body.categoryId(), body.placeId(), body.description());
    }

    @PostMapping("/{id}/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void discard(@PathVariable UUID id) {
        inbox.discard(id);
    }
}
