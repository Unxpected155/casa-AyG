package com.casaayg.invoice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * Lee el buzón de facturas cada dos horas de día (ADR-006). Por cada correo nuevo con adjuntos
 * XML: junta la factura con la respuesta de Hacienda (aunque lleguen en correos distintos), la
 * verifica, busca de quién es por la cédula del receptor y la deja POR REVISAR a su dueño.
 * Nada entra solo como movimiento. No guarda el correo ni los XML: solo el id del correo, la
 * clave y el resultado.
 */
@Component
class MailboxReader {

    private static final Logger log = LoggerFactory.getLogger(MailboxReader.class);
    static final String QUERY = "has:attachment filename:xml newer_than:60d";
    static final int MAX_PER_RUN = 20;

    record Piece(boolean response, String clave, boolean ticket, byte[] xml) {}

    public record RunResult(int read, int added) {}

    private final JdbcClient jdbc;
    private final GmailApi gmail;
    private final MailboxService mailbox;
    private final InvoiceVerifier verifier;
    private final TaxIdService taxIds;
    private final InvoiceInbox inbox;
    private final ApplicationEventPublisher events;
    private final boolean enabled;

    MailboxReader(JdbcClient jdbc, GmailApi gmail, MailboxService mailbox, InvoiceVerifier verifier,
                  TaxIdService taxIds, InvoiceInbox inbox, ApplicationEventPublisher events,
                  @Value("${casaayg.invoices.mailbox-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.gmail = gmail;
        this.mailbox = mailbox;
        this.verifier = verifier;
        this.taxIds = taxIds;
        this.inbox = inbox;
        this.events = events;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 5 7-21/2 * * *", zone = "America/Costa_Rica")
    void scheduled() {
        if (enabled) {
            run();
        }
    }

    /** Una pasada por el buzón. synchronized: la programada y «Revisar ahora» no se cruzan. */
    public synchronized RunResult run() {
        if (!mailbox.available()) {
            return new RunResult(0, 0);
        }
        String refresh;
        try {
            refresh = mailbox.currentToken();
        } catch (RuntimeException e) {
            error("No se pudo leer el permiso guardado: vuelve a conectar el buzón.");
            return new RunResult(0, 0);
        }
        if (refresh == null) {
            return new RunResult(0, 0);
        }
        String access;
        try {
            access = gmail.accessToken(refresh);
        } catch (RuntimeException e) {
            error("Google no aceptó el permiso: vuelve a conectar el buzón.");
            return new RunResult(0, 0);
        }
        int read = 0;
        int added = 0;
        try {
            for (String id : gmail.listMessages(access, QUERY, 50)) {
                if (read >= MAX_PER_RUN) {
                    break;
                }
                boolean seen = jdbc.sql("SELECT EXISTS (SELECT 1 FROM mailbox_message WHERE message_id = ?)")
                    .param(id).query(Boolean.class).single();
                if (seen) {
                    continue;
                }
                read++;
                added += process(access, id) ? 1 : 0;
            }
        } catch (RuntimeException e) {
            // Un problema de red: lo que no se leyó se lee en la próxima pasada.
            log.warn("No se pudo leer el buzón: {}", e.getClass().getSimpleName());
            error("No se pudo leer el buzón. Se vuelve a intentar en la próxima revisión.");
            return new RunResult(read, added);
        }
        jdbc.sql("UPDATE mailbox SET last_check_at = now(), last_error = NULL WHERE id = 1").update();
        return new RunResult(read, added);
    }

    /** Un correo. true si dejó una factura nueva por revisar. */
    private boolean process(String access, String messageId) {
        GmailApi.Mail mail = gmail.mail(access, messageId);
        List<Piece> pieces = pieces(mail.attachments());
        if (pieces.isEmpty()) {
            record(messageId, null, false, false, "SIN_XML", null);
            return false;
        }
        Map<String, List<Piece>> byClave = new LinkedHashMap<>();
        pieces.forEach(p -> byClave.computeIfAbsent(p.clave(), k -> new ArrayList<>()).add(p));
        boolean added = false;
        String clave = byClave.keySet().iterator().next();
        List<Piece> mine = byClave.get(clave);
        boolean hasInvoice = mine.stream().anyMatch(p -> !p.response());
        boolean hasResponse = mine.stream().anyMatch(Piece::response);

        Optional<byte[]> invoice = find(mine, false).or(() -> fromEarlier(access, clave, false));
        Optional<byte[]> response = find(mine, true).or(() -> fromEarlier(access, clave, true));
        // Un tiquete puede entrar sin la respuesta de Hacienda (queda «sin confirmar»); una factura no.
        boolean ticket = invoice.flatMap(InvoiceVerifier::peek).map(InvoiceVerifier.Peek::ticket).orElse(false);
        if (invoice.isEmpty() || (response.isEmpty() && !ticket)) {
            record(messageId, clave, hasInvoice, hasResponse, "INCOMPLETA", null);
            return false;
        }
        try {
            VerifiedInvoice v = verifier.verify(invoice.get(), response.orElse(null));
            // La respuesta de Hacienda de un tiquete que ya había entrado sin ella (suele llegar sola,
            // en otro correo, sin la variante del correo de nadie).
            if (v.haciendaConfirmed() && v.kind() == VerifiedInvoice.Kind.TIQUETE && inbox.haciendaConfirmed(v)) {
                record(messageId, clave, hasInvoice, hasResponse, "CONFIRMADA", null);
                return false;
            }
            // De quién es: la cédula si la trae; un tiquete sin cédula, por la variante del correo.
            Optional<UUID> owner = v.receptorId() != null ? taxIds.ownerOf(v.receptorId())
                : ownerByAddress(mail.recipients());
            if (owner.isEmpty()) {
                record(messageId, clave, hasInvoice, hasResponse, v.receptorId() == null ? "SIN_DUENO" : "AJENA", null);
            } else if (inbox.add(owner.get(), v)) {
                // Sin verificar: se anota quién firmó (dato público) para configurar las autoridades.
                record(messageId, clave, hasInvoice, hasResponse, "NUEVA", v.diagnostic());
                // El aviso sale en el resumen de las 6 p. m. (PushService.invoiceDigest), no uno por factura.
                added = true;
            } else {
                record(messageId, clave, hasInvoice, hasResponse, "REPETIDA", null);
            }
        } catch (InvoiceRejected e) {
            String detail = e.detail() == null ? e.getMessage() : e.getMessage() + " " + e.detail();
            record(messageId, clave, hasInvoice, hasResponse, "RECHAZADA", detail);
        }
        return added;
    }

    private List<Piece> pieces(List<GmailApi.Attachment> attachments) {
        List<Piece> out = new ArrayList<>();
        for (GmailApi.Attachment a : attachments) {
            InvoiceVerifier.peek(a.content())
                .ifPresent(p -> out.add(new Piece(p.response(), p.clave(), p.ticket(), a.content())));
        }
        return out;
    }

    private static Optional<byte[]> find(List<Piece> pieces, boolean response) {
        return pieces.stream().filter(p -> p.response() == response).map(Piece::xml).findFirst();
    }

    /** La otra mitad, de un correo anterior con la misma clave (se vuelve a pedir a Gmail). */
    private Optional<byte[]> fromEarlier(String access, String clave, boolean response) {
        List<String> ids = jdbc.sql("""
                SELECT message_id FROM mailbox_message
                 WHERE clave = ? AND (CASE WHEN ? THEN has_response ELSE has_invoice END)
                 ORDER BY processed_at DESC LIMIT 3""")
            .params(clave, response).query(String.class).list();
        for (String id : ids) {
            Optional<byte[]> found = pieces(gmail.xmlAttachments(access, id)).stream()
                .filter(p -> p.response() == response && p.clave().equals(clave)).map(Piece::xml).findFirst();
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** La persona dueña de la variante del correo de la casa a la que llegó el tiquete, si alguna. */
    private Optional<UUID> ownerByAddress(List<String> recipients) {
        String mailboxEmail = jdbc.sql("SELECT email FROM mailbox WHERE id = 1").query(String.class).optional().orElse(null);
        if (mailboxEmail == null) {
            return Optional.empty();
        }
        for (String recipient : recipients) {
            Optional<UUID> user = aliasTag(recipient, mailboxEmail)
                .flatMap(tag -> jdbc.sql("SELECT id FROM auth_find_user(?)").param(tag).query(UUID.class).optional());
            if (user.isPresent()) {
                return user;
            }
        }
        return Optional.empty();
    }

    /**
     * El usuario de una variante del correo de la casa: casa+gabito@gmail.com → «gabito».
     * Gmail no distingue puntos ni mayúsculas en su parte local: c.a.s.a+gabito@gmail.com también.
     */
    static Optional<String> aliasTag(String recipient, String mailboxEmail) {
        int at = recipient.lastIndexOf('@');
        int mailboxAt = mailboxEmail.lastIndexOf('@');
        if (at < 1 || mailboxAt < 1) {
            return Optional.empty();
        }
        String domain = recipient.substring(at + 1).toLowerCase(java.util.Locale.ROOT);
        String mailboxDomain = mailboxEmail.substring(mailboxAt + 1).toLowerCase(java.util.Locale.ROOT);
        if (!domain.equals(mailboxDomain)) {
            return Optional.empty();
        }
        String local = recipient.substring(0, at).toLowerCase(java.util.Locale.ROOT);
        int plus = local.indexOf('+');
        if (plus < 1) {
            return Optional.empty();
        }
        boolean gmail = domain.equals("gmail.com") || domain.equals("googlemail.com");
        String base = local.substring(0, plus);
        String mailboxBase = mailboxEmail.substring(0, mailboxAt).toLowerCase(java.util.Locale.ROOT);
        if (gmail) {
            base = base.replace(".", "");
            mailboxBase = mailboxBase.replace(".", "");
        }
        String tag = local.substring(plus + 1);
        return base.equals(mailboxBase) && tag.matches("[a-z][a-z0-9_]{2,29}") ? Optional.of(tag) : Optional.empty();
    }

    private void record(String messageId, String clave, boolean invoice, boolean response, String result, String detail) {
        jdbc.sql("""
                INSERT INTO mailbox_message (message_id, clave, has_invoice, has_response, result, detail)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (message_id) DO NOTHING""")
            .params(messageId, clave, invoice, response, result,
                detail == null ? null : (detail.length() > 300 ? detail.substring(0, 300) : detail))
            .update();
    }

    private void error(String message) {
        jdbc.sql("UPDATE mailbox SET last_check_at = now(), last_error = ? WHERE id = 1").param(message).update();
    }
}
