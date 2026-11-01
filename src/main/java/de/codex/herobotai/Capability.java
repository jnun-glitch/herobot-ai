package de.codex.herobotai;

public enum Capability {
    MOVEMENT("Bewegen und Strafen"),
    AIMING("Gegner anvisieren"),
    SWORD_TIMING("Schwert-Timing"),
    CRITICAL_HITS("Springen und Crits"),
    SHIELD("Schild benutzen"),
    HEALING("Essen und Heilen"),
    HOTBAR("Hotbar wechseln"),
    CRYSTAL_PVP("Crystal-PvP"),
    MACE_ELYTRA("Mace und Elytra");

    private final String label;

    Capability(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
