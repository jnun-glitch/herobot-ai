package de.codex.herobotai;

public enum CapabilityStatus {
    UNKNOWN("Unbekannt"),
    CAN("Kann"),
    CANNOT("Kann nicht");

    private final String label;

    CapabilityStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public CapabilityStatus next() {
        return switch (this) {
            case UNKNOWN -> CAN;
            case CAN -> CANNOT;
            case CANNOT -> UNKNOWN;
        };
    }
}
