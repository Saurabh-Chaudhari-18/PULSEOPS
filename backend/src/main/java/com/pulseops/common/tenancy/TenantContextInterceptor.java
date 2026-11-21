package com.pulseops.common.tenancy;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.pulseops.common.error.ApiException;
import com.pulseops.common.security.CurrentUser;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Establishes the tenant context for every authenticated API request.
 *
 * <ol>
 *   <li>Binds the authenticated user ID (RLS: user-scoped membership visibility).</li>
 *   <li>For routes with an {@code {orgId}} path variable: verifies an ACTIVE membership in
 *       an ACTIVE organization, enforces {@link RequiresPermission}, then binds the
 *       organization ID (RLS: tenant isolation).</li>
 * </ol>
 *
 * Non-members get 404 rather than 403 so organization IDs cannot be probed for existence.
 */
@Component
public class TenantContextInterceptor implements HandlerInterceptor {

    static final String ORG_PATH_VARIABLE = "orgId";

    private final OrgAccessResolver accessResolver;

    public TenantContextInterceptor(OrgAccessResolver accessResolver) {
        this.accessResolver = accessResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        TenantContext.clear();
        UUID userId = CurrentUser.idOrNull();
        TenantContext.setUserId(userId);

        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresPermission required = method.getMethodAnnotation(RequiresPermission.class);
        String rawOrgId = orgPathVariable(request);
        if (rawOrgId == null) {
            if (required != null) {
                // Programming error: a permission without an organization scope must never pass silently.
                throw new IllegalStateException("@RequiresPermission on a route without {orgId}: " + method);
            }
            return true;
        }
        if (userId == null) {
            throw ApiException.unauthorized("UNAUTHENTICATED", "Authentication required");
        }
        UUID orgId = parseUuid(rawOrgId);
        OrgAccess access = accessResolver.resolve(orgId, userId)
                .orElseThrow(TenantContextInterceptor::organizationNotFound);
        if (required != null && !access.has(required.value())) {
            throw ApiException.forbidden("MISSING_PERMISSION",
                    "Your role does not grant the '" + required.value() + "' permission");
        }
        TenantContext.setOrganizationId(orgId);
        request.setAttribute(OrgAccess.REQUEST_ATTRIBUTE, access);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private static String orgPathVariable(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return vars instanceof Map<?, ?> map ? ((Map<String, String>) map).get(ORG_PATH_VARIABLE) : null;
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw organizationNotFound();
        }
    }

    private static ApiException organizationNotFound() {
        return ApiException.notFound("ORGANIZATION_NOT_FOUND", "Organization not found");
    }
}
