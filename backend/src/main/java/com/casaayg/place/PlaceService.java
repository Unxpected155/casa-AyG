package com.casaayg.place;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.casaayg.domain.Money;
import com.casaayg.domain.Text.PlaceName;
import com.casaayg.shared.security.CurrentUser;
import com.casaayg.shared.web.ConflictException;
import com.casaayg.shared.web.NotFoundException;
import com.casaayg.space.Spaces;

/**
 * Lugares donde vive el dinero y el dinero movido entre ellos (V6).
 * El saldo se calcula siempre desde los movimientos: no hay un saldo guardado que pueda
 * desincronizarse.
 */
@Service
public class PlaceService {

    public static final int MAX_BATCH = 20;

    public enum Kind { BANCO, SOBRE, CDP, EFECTIVO }

    private final JdbcClient jdbc;
    private final Spaces spaces;

    PlaceService(JdbcClient jdbc, Spaces spaces) {
        this.jdbc = jdbc;
        this.spaces = spaces;
    }

    public record NewPlace(PlaceName name, Kind kind, Money opening, LocalDate openingDate, UUID parentId) {}

    public record PlaceChanges(PlaceName name, BigDecimal openingBalance, LocalDate openingDate, UUID parentId,
                               boolean archived) {}

    public record PlaceView(UUID id, String name, Kind kind, Money.Currency currency, BigDecimal openingBalance,
                            LocalDate openingDate, UUID parentId, boolean archived, BigDecimal balance, Cdp cdp) {}

    /** Datos de un CDP (null en los demás lugares; sus campos quedan null hasta configurarlo). */
    public record Cdp(BigDecimal rate, Integer termDays, Integer dayBasis, BigDecimal taxPct, LocalDate maturesOn,
                      UUID payoutPlace, BigDecimal lastContribution) {}

    public record NewTransfer(UUID from, UUID to, LocalDate date, BigDecimal amount, String note) {}

    public record TransferView(UUID id, UUID fromPlace, UUID toPlace, LocalDate date, BigDecimal amount,
                               Money.Currency currency, String description, UUID batchId, UUID createdBy) {}

    /*
     * Saldo a HOY: saldo inicial + ingresos − gastos ± dinero movido, desde la fecha del saldo
     * inicial. Lo anotado con fecha futura todavía no cuenta.
     */
    private static final String PLACE_SELECT = """
        SELECT p.id, p.name, p.kind, p.currency, p.opening_balance, p.opening_date, p.parent_id, p.archived,
               p.cdp_rate, p.cdp_term_days, p.cdp_day_basis, p.cdp_tax_pct, p.cdp_matures_on, p.cdp_payout_place,
               p.cdp_last_contribution,
               place_balance(p.id, current_date) AS balance
          FROM place p""";

    @Transactional(readOnly = true)
    public List<PlaceView> list(UUID spaceId) {
        spaces.requireMember(spaceId);
        return jdbc.sql(PLACE_SELECT + " WHERE p.space_id = ? ORDER BY p.archived, p.created_at")
            .param(spaceId).query(PlaceService::mapPlace).list();
    }

    @Transactional
    public PlaceView create(UUID spaceId, NewPlace place) {
        spaces.requireWritable(spaceId);
        if (place.parentId() != null) {
            requireValidParent(spaceId, null, place.kind(), place.opening().currency(), place.parentId());
        }
        UUID id = jdbc.sql("""
                INSERT INTO place (space_id, created_by, name, kind, currency, opening_balance, opening_date, parent_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
            .params(spaceId, CurrentUser.require(), place.name().value(), place.kind().name(),
                place.opening().currency().name(), place.opening().amount(), place.openingDate(), place.parentId())
            .query(UUID.class).single();
        return find(spaceId, id);
    }

    @Transactional
    public PlaceView update(UUID spaceId, UUID id, PlaceChanges changes) {
        spaces.requireWritable(spaceId);
        PlaceView current = find(spaceId, id);
        if (changes.parentId() != null) {
            requireValidParent(spaceId, id, current.kind(), current.currency(), changes.parentId());
        }
        Money opening = new Money(changes.openingBalance(), current.currency());
        jdbc.sql("""
                UPDATE place SET name = ?, opening_balance = ?, opening_date = ?, parent_id = ?, archived = ?
                 WHERE id = ? AND space_id = ?""")
            .params(changes.name().value(), opening.amount(), changes.openingDate(), changes.parentId(),
                changes.archived(), id, spaceId)
            .update();
        return find(spaceId, id);
    }

    @Transactional(readOnly = true)
    public List<TransferView> transfers(UUID spaceId, YearMonth month) {
        spaces.requireMember(spaceId);
        return jdbc.sql("""
                SELECT x.id, x.from_place, x.to_place, x.tx_date, x.amount, p.currency, x.description, x.batch_id,
                       x.created_by
                  FROM transfer x JOIN place p ON p.id = x.from_place
                 WHERE x.space_id = ? AND x.tx_date >= ? AND x.tx_date < ?
                 ORDER BY x.tx_date DESC, x.created_at DESC""")
            .params(spaceId, month.atDay(1), month.plusMonths(1).atDay(1))
            .query(PlaceService::mapTransfer).list();
    }

    @Transactional
    public TransferView transfer(UUID spaceId, NewTransfer transfer) {
        spaces.requireWritable(spaceId);
        UUID id = insertTransfer(spaceId, transfer, null);
        return findTransfer(spaceId, id);
    }

    /** "Repartir": varias transferencias desde un mismo lugar, todas o ninguna. */
    @Transactional
    public List<TransferView> distribute(UUID spaceId, UUID from, LocalDate date, String note,
                                         List<NewTransfer> items) {
        spaces.requireWritable(spaceId);
        if (items.isEmpty() || items.size() > MAX_BATCH) {
            throw new IllegalArgumentException("entre 1 y " + MAX_BATCH + " destinos");
        }
        if (items.stream().map(NewTransfer::to).distinct().count() != items.size()) {
            throw new IllegalArgumentException("destino repetido");
        }
        UUID batch = UUID.randomUUID();
        return items.stream()
            .map(item -> insertTransfer(spaceId, new NewTransfer(from, item.to(), date, item.amount(), note), batch))
            .map(id -> findTransfer(spaceId, id))
            .toList();
    }

    @Transactional
    public void deleteTransfer(UUID spaceId, UUID id) {
        spaces.requireWritable(spaceId);
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM month_close_item WHERE transfer_id = ?)").param(id)
                .query(Boolean.class).single()) {
            throw new ConflictException("Esto lo movió el cierre del mes. Para cambiarlo, reabran el mes en «La casa».");
        }
        if (jdbc.sql("DELETE FROM transfer WHERE id = ? AND space_id = ?").params(id, spaceId).update() == 0) {
            throw new NotFoundException();
        }
    }

    /**
     * Un lugar ajeno o inexistente responde 404. Lo usa también el servicio de movimientos,
     * que acepta lugares archivados (para poder editar lo anotado antes de archivarlos).
     * @return la moneda del lugar.
     */
    public Money.Currency requirePlace(UUID spaceId, UUID placeId, boolean allowArchived) {
        return jdbc.sql("SELECT currency FROM place WHERE id = ? AND space_id = ? AND (? OR NOT archived)")
            .params(placeId, spaceId, allowArchived).query(String.class).optional()
            .map(Money.Currency::valueOf)
            .orElseThrow(NotFoundException::new);
    }

    private UUID insertTransfer(UUID spaceId, NewTransfer t, UUID batch) {
        if (t.from().equals(t.to())) {
            throw new IllegalArgumentException("origen y destino iguales");
        }
        if (requirePlace(spaceId, t.from(), false) != requirePlace(spaceId, t.to(), false)) {
            throw new ConflictException("Solo se puede mover dinero entre lugares de la misma moneda.");
        }
        Money amount = new Money(t.amount(), Money.Currency.CRC); // valida rango y decimales
        if (amount.amount().signum() == 0) {
            throw new IllegalArgumentException("monto en cero");
        }
        return jdbc.sql("""
                INSERT INTO transfer (space_id, created_by, from_place, to_place, tx_date, amount, description, batch_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
            .params(spaceId, CurrentUser.require(), t.from(), t.to(), t.date(), amount.amount(), t.note(), batch)
            .query(UUID.class).single();
    }

    /** Solo un sobre va dentro de otro lugar, de la misma moneda, y sin anidar más de un nivel. */
    private void requireValidParent(UUID spaceId, UUID self, Kind kind, Money.Currency currency, UUID parentId) {
        if (kind != Kind.SOBRE || parentId.equals(self)) {
            throw new IllegalArgumentException("solo un sobre va dentro de otro lugar");
        }
        PlaceView parent = find(spaceId, parentId);
        if (parent.kind() == Kind.SOBRE || parent.currency() != currency) {
            throw new IllegalArgumentException("el sobre va dentro de un lugar de su misma moneda");
        }
    }

    private PlaceView find(UUID spaceId, UUID id) {
        return jdbc.sql(PLACE_SELECT + " WHERE p.id = ? AND p.space_id = ?")
            .params(id, spaceId).query(PlaceService::mapPlace).optional()
            .orElseThrow(NotFoundException::new);
    }

    private TransferView findTransfer(UUID spaceId, UUID id) {
        return jdbc.sql("""
                SELECT x.id, x.from_place, x.to_place, x.tx_date, x.amount, p.currency, x.description, x.batch_id,
                       x.created_by
                  FROM transfer x JOIN place p ON p.id = x.from_place
                 WHERE x.id = ? AND x.space_id = ?""")
            .params(id, spaceId).query(PlaceService::mapTransfer).optional()
            .orElseThrow(NotFoundException::new);
    }

    private static PlaceView mapPlace(ResultSet rs, int n) throws SQLException {
        Kind kind = Kind.valueOf(rs.getString("kind"));
        Cdp cdp = kind != Kind.CDP ? null : new Cdp(rs.getBigDecimal("cdp_rate"),
            rs.getObject("cdp_term_days", Integer.class), rs.getObject("cdp_day_basis", Integer.class),
            rs.getBigDecimal("cdp_tax_pct"), rs.getObject("cdp_matures_on", LocalDate.class),
            rs.getObject("cdp_payout_place", UUID.class), rs.getBigDecimal("cdp_last_contribution"));
        return new PlaceView(rs.getObject("id", UUID.class), rs.getString("name"), kind,
            Money.Currency.valueOf(rs.getString("currency")), rs.getBigDecimal("opening_balance"),
            rs.getObject("opening_date", LocalDate.class), rs.getObject("parent_id", UUID.class),
            rs.getBoolean("archived"), rs.getBigDecimal("balance"), cdp);
    }

    private static TransferView mapTransfer(ResultSet rs, int n) throws SQLException {
        return new TransferView(rs.getObject("id", UUID.class), rs.getObject("from_place", UUID.class),
            rs.getObject("to_place", UUID.class), rs.getObject("tx_date", LocalDate.class), rs.getBigDecimal("amount"),
            Money.Currency.valueOf(rs.getString("currency")), rs.getString("description"),
            rs.getObject("batch_id", UUID.class), rs.getObject("created_by", UUID.class));
    }
}
