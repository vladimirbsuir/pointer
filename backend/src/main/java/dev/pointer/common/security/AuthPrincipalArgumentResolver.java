package dev.pointer.common.security;

import dev.pointer.common.error.ApiException;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class AuthPrincipalArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(AuthPrincipal.class);
    }

    @Override
    public AuthPrincipal resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw ApiException.unauthorized("unauthenticated", "Authentication is required");
        }
        var deviceClaim = jwt.getClaimAsString(AuthPrincipal.DEVICE_CLAIM);
        return new AuthPrincipal(
                UUID.fromString(jwt.getSubject()), deviceClaim == null ? null : UUID.fromString(deviceClaim));
    }
}
