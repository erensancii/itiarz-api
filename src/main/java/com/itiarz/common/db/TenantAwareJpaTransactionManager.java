package com.itiarz.common.db;

import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.itiarz.security.RequestContext;
import com.itiarz.security.RequestContextHolder;

/**
 * Applies the RLS settings (spec §5) at the start of every physical transaction, on the same
 * connection, as transaction-local values ({@code set_config(..., true)}) so nothing leaks through
 * the pool. Platform mode sets {@code app.platform='on'} and never a tenant id at the same time.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    private static final String SET_CONTEXT = "select set_config('app.user_id', ?, true), "
            + "set_config('app.tenant_id', ?, true), set_config('app.platform', ?, true)";

    public TenantAwareJpaTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        Optional<RequestContext> context = RequestContextHolder.current();
        boolean platform = RequestContextHolder.platformMode();
        String userId = context.map(RequestContext::userId).map(UUID::toString).orElse("");
        String tenantId = platform ? ""
                : context.map(RequestContext::tenantId).map(UUID::toString).orElse("");
        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        EntityManager em = holder.getEntityManager();
        try {
            em.unwrap(Session.class).doWork(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(SET_CONTEXT)) {
                    statement.setString(1, userId);
                    statement.setString(2, tenantId);
                    statement.setString(3, platform ? "on" : "");
                    statement.execute();
                }
            });
        } catch (RuntimeException e) {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            doCleanupAfterCompletion(transaction);
            throw new CannotCreateTransactionException("Could not apply RLS context to transaction", e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {
        @Bean
        TenantAwareJpaTransactionManager transactionManager(
                EntityManagerFactory emf, ObjectProvider<TransactionManagerCustomizers> customizers) {
            TenantAwareJpaTransactionManager manager = new TenantAwareJpaTransactionManager(emf);
            customizers.ifAvailable(c -> c.customize(manager));
            return manager;
        }
    }
}
