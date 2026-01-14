package com.company.observability.exception;

public class AlertSendException extends RuntimeException {
    public AlertSendException(String message, Throwable cause) {
        super(message, cause);
    }
}