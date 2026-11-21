package com.pulseops.common.tenancy;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Injects the verified {@link OrgAccess} into controller methods on {@code {orgId}} routes. */
@Component
public class OrgAccessArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return OrgAccess.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object access = webRequest.getAttribute(OrgAccess.REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (access == null) {
            throw new IllegalStateException("OrgAccess requested on a route without a verified {orgId}");
        }
        return access;
    }
}
