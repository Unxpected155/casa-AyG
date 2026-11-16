package com.casaayg.shared.db;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
class DatabaseConfig {

    /** Reemplaza al transaction manager de Spring Boot (que se retira si existe uno propio). */
    @Bean
    RlsTransactionManager transactionManager(DataSource dataSource) {
        return new RlsTransactionManager(dataSource);
    }
}
