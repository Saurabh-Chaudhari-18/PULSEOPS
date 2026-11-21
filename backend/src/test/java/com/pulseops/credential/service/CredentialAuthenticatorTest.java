package com.pulseops.credential.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.pulseops.common.error.ApiException;
import com.pulseops.common.tenancy.TenantContext;
import com.pulseops.credential.domain.IntegrationKeys;
import com.pulseops.testsupport.MutableClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class CredentialAuthenticatorTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final MutableClock clock = MutableClock.startingAt("2026-03-01T10:00:00Z");
    private final CredentialAuthenticator authenticator = new CredentialAuthenticator(jdbc, tx, clock);

    private final String key = IntegrationKeys.generate();
    private final UUID credentialId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void malformedKeyIsRejectedWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> authenticator.authenticate("Bearer something"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_CREDENTIAL");
        verifyNoInteractions(jdbc, tx);
    }

    @Test
    void lookupBindsTheHashAndReturnsTheScope() throws Exception {
        stubRow("active", null);

        CredentialAuthenticator.AuthenticatedKey auth = authenticator.authenticate(key);

        assertThat(auth).isEqualTo(new CredentialAuthenticator.AuthenticatedKey(credentialId, orgId, projectId, null));
        verify(jdbc).queryForObject(eq("SELECT set_config('app.credential_hash', ?, true)"), eq(String.class),
                eq(IntegrationKeys.hash(key)));
    }

    @Test
    void unknownRevokedAndExpiredKeysAreDistinguished() throws Exception {
        stubRows(List.of());
        assertCode("INVALID_CREDENTIAL");

        stubRow("revoked", null);
        assertCode("CREDENTIAL_REVOKED");

        stubRow("active", clock.instant().minusSeconds(1));
        assertCode("CREDENTIAL_EXPIRED");

        stubRow("expired", null);
        assertCode("CREDENTIAL_EXPIRED");
    }

    @Test
    void refusesToRunInsideATenantContext() {
        TenantContext.setOrganizationId(UUID.randomUUID());
        assertThatThrownBy(() -> authenticator.authenticate(key)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lastUsedIsWrittenAtMostOncePerMinute() {
        var auth = new CredentialAuthenticator.AuthenticatedKey(credentialId, orgId, projectId, null);

        authenticator.markUsed(auth);
        authenticator.markUsed(auth);
        clock.advance(Duration.ofSeconds(30));
        authenticator.markUsed(auth);
        verify(jdbc, times(1)).update(anyString(), any(Timestamp.class), eq(credentialId), eq(orgId));

        clock.advance(Duration.ofSeconds(31));
        authenticator.markUsed(auth);
        verify(jdbc, times(2)).update(anyString(), any(Timestamp.class), eq(credentialId), eq(orgId));
    }

    private void assertCode(String code) {
        assertThatThrownBy(() -> authenticator.authenticate(key))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(code);
    }

    private void stubRow(String status, Instant expiresAt) throws java.sql.SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(credentialId);
        when(rs.getObject("organization_id", UUID.class)).thenReturn(orgId);
        when(rs.getObject("project_id", UUID.class)).thenReturn(projectId);
        when(rs.getObject("service_id", UUID.class)).thenReturn(null);
        when(rs.getString("status")).thenReturn(status);
        when(rs.getTimestamp("expires_at")).thenReturn(expiresAt == null ? null : Timestamp.from(expiresAt));
        stubQuery(rs);
    }

    // doAnswer/doReturn style: re-stubbing with when(...) would invoke the previous answer.

    @SuppressWarnings("unchecked")
    private void stubQuery(ResultSet rs) {
        runTransactionsInline();
        doAnswer(inv -> {
            RowMapper<Object> mapper = inv.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), eq(IntegrationKeys.hash(key)));
    }

    @SuppressWarnings("unchecked")
    private void stubRows(List<Object> rows) {
        runTransactionsInline();
        doReturn(rows).when(jdbc).query(anyString(), any(RowMapper.class), eq(IntegrationKeys.hash(key)));
    }

    @SuppressWarnings("unchecked")
    private void runTransactionsInline() {
        doAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null))
                .when(tx).execute(any());
    }
}
