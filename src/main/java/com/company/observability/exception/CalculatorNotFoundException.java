package com.company.observability.exception;

public class CalculatorNotFoundException extends RuntimeException {
    public CalculatorNotFoundException(String calculatorId) {
        super("Calculator not found: " + calculatorId);
    }
}