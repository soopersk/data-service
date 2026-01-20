package com.company.observability.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Extract tenant ID from authenticated user context
 * Prevents tenant spoofing via X-Tenant-Id header
 */
@Component
@Slf4j
public class TenantContext {

    /**
     * Get tenant ID from JWT token claim
     * Falls back to "default" if not present
     */
    public String getCurrentTenantId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("No authenticated user, using default tenant");
            return "default";
        }

        if (authentication.getPrincipal() instanceof Jwt jwt) {
            // Extract tenant from JWT claim
            // Azure AD custom claim: "extension_TenantId" or "tenant_id"
            String tenantId = jwt.getClaimAsString("tenant_id");

            if (tenantId == null) {
                tenantId = jwt.getClaimAsString("extension_TenantId");
            }

            if (tenantId == null) {
                log.warn("No tenant_id claim in JWT, using default tenant");
                return "default";
            }

            return tenantId;
        }

        log.warn("Unexpected authentication principal type: {}",
                authentication.getPrincipal().getClass());
        return "default";
    }

    /**
     * Get user ID from JWT token
     */
    public String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("sub"); // Subject claim
        }

        return "anonymous";
    }

    /**
     * Get user email from JWT token
     */
    public String getCurrentUserEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String email = jwt.getClaimAsString("email");
            if (email == null) {
                email = jwt.getClaimAsString("preferred_username");
            }
            return email;
        }

        return null;
    }
}