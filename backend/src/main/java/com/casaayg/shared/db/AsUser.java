package com.casaayg.shared.db;

import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ejecuta trabajo en una transacción NUEVA a nombre de un usuario concreto, para momentos
 * en que todavía no hay sesión de passkey (justo al iniciar sesión, auditoría de un login).
 * Úsalo solo con un id que ya se autenticó: RLS confía en lo que se le diga aquí.
 */
@Component
public class AsUser {

    private final TransactionTemplate tx;
    private final JdbcClient jdbc;

    AsUser(PlatformTransactionManager transactionManager, JdbcClient jdbc) {
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.jdbc = jdbc;
    }

    public <T> T run(UUID userId, Supplier<T> work) {
        return tx.execute(status -> {
            jdbc.sql("SELECT set_config('app.current_user_id', ?, true)")
                .param(userId == null ? "" : userId.toString())
                .query().singleValue();
            return work.get();
        });
    }
}
