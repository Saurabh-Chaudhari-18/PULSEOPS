package com.pulseops.audit.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AuditServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final AuditService audit = new AuditService(jdbc, new ObjectMapper(), entityManager);

    @AfterEach
    void reset() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    /**
     * Regression test: the audit row references (FK) rows that JPA may not have written yet,
     * e.g. a just-registered user. Pending JPA changes must reach the database first.
     */
    @Test
    void flushesPendingJpaWritesBeforeInsertingInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        UUID user = UUID.randomUUID();

        audit.record(user, "user.registered", "user", user, Map.of());

        InOrder order = inOrder(entityManager, jdbc);
        order.verify(entityManager).flush();
        order.verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void doesNotFlushOutsideATransaction() {
        audit.record(null, "auth.login_failed", "user", null, Map.of());

        verify(entityManager, never()).flush();
        verify(jdbc).update(anyString(), any(Object[].class));
    }
}
