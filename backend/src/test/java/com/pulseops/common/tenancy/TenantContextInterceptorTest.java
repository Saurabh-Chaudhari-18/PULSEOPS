package com.pulseops.common.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.pulseops.common.error.ApiException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

class TenantContextInterceptorTest {

    static class Handlers {
        @RequiresPermission(Permissions.PROJECT_MANAGE)
        public void manage() {
        }

        public void read() {
        }
    }

    private final OrgAccessResolver resolver = mock(OrgAccessResolver.class);
    private final TenantContextInterceptor interceptor = new TenantContextInterceptor(resolver);
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void bindsUserAndOrganizationForAMemberWithPermission() throws Exception {
        authenticate();
        OrgAccess access = access(Set.of(Permissions.PROJECT_MANAGE));
        when(resolver.resolve(orgId, userId)).thenReturn(Optional.of(access));
        MockHttpServletRequest request = request(orgId.toString());

        assertThat(interceptor.preHandle(request, response, handler("manage"))).isTrue();

        assertThat(TenantContext.userId()).isEqualTo(userId);
        assertThat(TenantContext.organizationId()).isEqualTo(orgId);
        assertThat(request.getAttribute(OrgAccess.REQUEST_ATTRIBUTE)).isEqualTo(access);
    }

    @Test
    void nonMemberGets404AndNoTenantIsBound() throws Exception {
        authenticate();
        when(resolver.resolve(orgId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> interceptor.preHandle(request(orgId.toString()), response, handler("read")))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("ORGANIZATION_NOT_FOUND");
        assertThat(TenantContext.organizationId()).isNull();
    }

    @Test
    void memberWithoutPermissionGets403AndNoTenantIsBound() throws Exception {
        authenticate();
        when(resolver.resolve(orgId, userId)).thenReturn(Optional.of(access(Set.of(Permissions.PROJECT_READ))));

        assertThatThrownBy(() -> interceptor.preHandle(request(orgId.toString()), response, handler("manage")))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("MISSING_PERMISSION");
        assertThat(TenantContext.organizationId()).isNull();
    }

    @Test
    void malformedOrganizationIdIs404WithoutLookup() throws Exception {
        authenticate();

        assertThatThrownBy(() -> interceptor.preHandle(request("not-a-uuid"), response, handler("read")))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("ORGANIZATION_NOT_FOUND");
        verify(resolver, never()).resolve(any(), any());
    }

    @Test
    void permissionAnnotationWithoutOrgScopeFailsClosed() throws Exception {
        authenticate();

        assertThatThrownBy(() -> interceptor.preHandle(request(null), response, handler("manage")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void staleContextFromAPreviousRequestIsClearedFirst() throws Exception {
        TenantContext.setOrganizationId(UUID.randomUUID()); // leaked by a previous request on this thread
        authenticate();

        interceptor.preHandle(request(null), response, handler("read"));

        assertThat(TenantContext.organizationId()).isNull();
        assertThat(TenantContext.userId()).isEqualTo(userId);
    }

    @Test
    void afterCompletionClearsEverything() throws Exception {
        TenantContext.setOrganizationId(orgId);
        TenantContext.setUserId(userId);

        interceptor.afterCompletion(request(null), response, handler("read"), null);

        assertThat(TenantContext.organizationId()).isNull();
        assertThat(TenantContext.userId()).isNull();
    }

    private void authenticate() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject(userId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private OrgAccess access(Set<String> permissions) {
        return new OrgAccess(orgId, userId, UUID.randomUUID(), UUID.randomUUID(), "MEMBER", permissions);
    }

    private static MockHttpServletRequest request(String orgIdVariable) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                orgIdVariable == null ? Map.of() : Map.of("orgId", orgIdVariable));
        return request;
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Handlers(), Handlers.class.getMethod(name));
    }
}
