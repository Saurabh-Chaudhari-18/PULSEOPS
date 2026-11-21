package com.pulseops.common.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission code a controller method needs within the organization named by
 * its {@code {orgId}} path variable. Enforced by {@link TenantContextInterceptor} before the
 * controller runs. Methods with an {@code {orgId}} but no annotation require membership only.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** A code from {@link Permissions}. */
    String value();
}
