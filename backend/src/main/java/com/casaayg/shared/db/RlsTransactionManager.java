package com.casaayg.shared.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import com.casaayg.shared.security.CurrentUser;

/**
 * Cumple el contrato de V3 (RLS): al inicio de CADA transacción le dice a Postgres quién es
 * el usuario, con set_config(..., true), que dura solo hasta el fin de la transacción.
 *
 * Sin sesión de passkey se envía '' y la BD no muestra nada (falla cerrado). Una consulta
 * fuera de una transacción tampoco lleva usuario: por eso todo el acceso a datos es @Transactional.
 */
public class RlsTransactionManager extends DataSourceTransactionManager {

    public RlsTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(con, definition);
        String userId = CurrentUser.passkeyUserId().map(UUID::toString).orElse("");
        try (PreparedStatement ps = con.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            ps.setString(1, userId);
            ps.execute();
        }
    }
}
