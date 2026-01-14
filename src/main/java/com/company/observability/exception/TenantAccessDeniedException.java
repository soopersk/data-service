package com.company.observability.exception;

public class TenantAccessDeniedException extends RuntimeException {
    public TenantAccessDeniedException(String tenantId, String runId) {
        super("Tenant " + tenantId + " does not have access to run " + runId);
    }
}