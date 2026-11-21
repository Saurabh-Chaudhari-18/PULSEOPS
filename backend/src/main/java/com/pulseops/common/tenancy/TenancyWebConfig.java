package com.pulseops.common.tenancy;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class TenancyWebConfig implements WebMvcConfigurer {

    private final TenantContextInterceptor tenantContextInterceptor;
    private final OrgAccessArgumentResolver orgAccessArgumentResolver;

    public TenancyWebConfig(TenantContextInterceptor tenantContextInterceptor,
                            OrgAccessArgumentResolver orgAccessArgumentResolver) {
        this.tenantContextInterceptor = tenantContextInterceptor;
        this.orgAccessArgumentResolver = orgAccessArgumentResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantContextInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(orgAccessArgumentResolver);
    }
}
