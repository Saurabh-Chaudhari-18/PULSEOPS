package com.pulseops.common.tenancy;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Wraps the application DataSource in {@link TenantAwareDataSource} so JPA, JdbcTemplate
 * and Flyway all receive tenant-scoped connections. (Flyway runs with an empty context;
 * DDL is unaffected by RLS.)
 */
@Component
public class TenantDataSourcePostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)) {
            return new TenantAwareDataSource(dataSource);
        }
        return bean;
    }
}
