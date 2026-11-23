package com.casaayg.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.casaayg.domain.Money;
import com.casaayg.domain.Text.Description;
import com.casaayg.shared.web.NotFoundException;
import com.casaayg.transaction.TransactionService;
import com.casaayg.transaction.TransactionService.TransactionData;
import com.casaayg.transaction.TransactionService.TransactionView;

/**
 * Facturas por revisar (V16). Entran verificadas por la lectura del buzón y esperan a que su
 * dueño las confirme (se vuelven un movimiento) o las descarte. RLS: cada quien ve las suyas.
 */
@Service
public class InvoiceInbox {

    /** Dónde se anotó la última factura de ese mismo comercio (para sugerirlo). */
    public record Suggestion(UUID spaceId, UUID categoryId, UUID placeId) {}

    /** {@code verified}: la firma se confirmó contra una autoridad de confianza. */
    public record Pending(UUID id, String kind, LocalDate date, String merchant, Money.Currency currency,
                          BigDecimal total, boolean verified, Suggestion suggestion) {}

    private final JdbcClient jdbc;
    private final TransactionService transactions;

    InvoiceInbox(JdbcClient jdbc, TransactionService transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** Lo llama la lectura del buzón, sin sesión. false = esa factura ya había entrado. */
    public boolean add(UUID owner, VerifiedInvoice v) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT invoice_inbox_add(?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .params(owner, v.clave(), v.kind().name(), v.date(), v.merchant(), v.emisorId(), v.currency(), v.total(),
                v.signatureVerified())
            .query(Boolean.class).single());
    }

    @Transactional(readOnly = true)
    public List<Pending> pending() {
        return jdbc.sql("""
                SELECT i.id, i.kind, i.issued_on, i.merchant, i.currency, i.total,
                       s.space_id, s.category_id, s.place_id, i.signature_verified
                  FROM invoice_inbox i
                  LEFT JOIN LATERAL (
                       SELECT t.space_id, t.category_id, t.place_id
                         FROM invoice_inbox p JOIN transaction t ON t.id = p.transaction_id
                        WHERE p.emisor_id = i.emisor_id AND p.status = 'CONFIRMADA'
                          AND t.space_id IN (SELECT my_writable_space_ids())
                        ORDER BY p.decided_at DESC LIMIT 1) s ON true
                 WHERE i.status = 'PENDIENTE'
                 ORDER BY i.issued_on DESC, i.received_at DESC
                 LIMIT 100""")
            .query((rs, n) -> new Pending(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, LocalDate.class), rs.getString(4), Money.Currency.valueOf(rs.getString(5).strip()),
                rs.getBigDecimal(6), rs.getBoolean(10), rs.getObject(7) == null ? null
                    : new Suggestion(rs.getObject(7, UUID.class), rs.getObject(8, UUID.class), rs.getObject(9, UUID.class))))
            .list();
    }

    private record Row(String clave, LocalDate date, String merchant, Money.Currency currency, BigDecimal total) {}

    /**
     * Se vuelve un movimiento en {@code spaceId} (personal o de la casa), con la fecha, el monto
     * y la moneda de la factura: eso no se puede cambiar al confirmar.
     */
    @Transactional
    public TransactionView confirm(UUID id, UUID spaceId, UUID categoryId, UUID placeId, String description) {
        Row r = jdbc.sql("""
                SELECT clave, issued_on, merchant, currency, total FROM invoice_inbox
                 WHERE id = ? AND status = 'PENDIENTE' FOR UPDATE""")
            .param(id)
            .query((rs, n) -> new Row(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getString(3),
                Money.Currency.valueOf(rs.getString(4).strip()), rs.getBigDecimal(5)))
            .optional().orElseThrow(NotFoundException::new);
        String text = description == null || description.isBlank() ? r.merchant() : description;
        TransactionView tx = transactions.createFromInvoice(spaceId, new TransactionData(categoryId, r.date(),
            new Description(text), new Money(r.total(), r.currency()), null, placeId), r.clave());
        jdbc.sql("UPDATE invoice_inbox SET status = 'CONFIRMADA', transaction_id = ?, decided_at = now() WHERE id = ?")
            .params(tx.id(), id).update();
        return tx;
    }

    @Transactional
    public void discard(UUID id) {
        int rows = jdbc.sql("""
                UPDATE invoice_inbox SET status = 'DESCARTADA', decided_at = now()
                 WHERE id = ? AND status = 'PENDIENTE'""")
            .param(id).update();
        if (rows == 0) {
            throw new NotFoundException();
        }
    }
}
