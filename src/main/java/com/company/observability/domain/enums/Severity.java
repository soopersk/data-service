package com.company.observability.domain.enums;

public enum Severity {
    LOW(1, "Low severity - minor issue"),
    MEDIUM(2, "Medium severity - requires attention"),
    HIGH(3, "High severity - urgent attention needed"),
    CRITICAL(4, "Critical severity - immediate action required");

    private final int level;
    private final String description;

    Severity(int level, String description) {
        this.level = level;
        this.description = description;
    }

    public int getLevel() {
        return level;
    }

    public String getDescription() {
        return description;
    }

    public boolean isHigherThan(Severity other) {
        return this.level > other.level;
    }

    public static Severity fromString(String severity) {
        if (severity == null) {
            return MEDIUM;
        }
        try {
            return Severity.valueOf(severity.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MEDIUM;
        }
    }
}