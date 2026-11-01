package de.codex.herobotai;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

public final class TrainingStore {
    private static final Map<Capability, CapabilityStatus> STATUSES = new EnumMap<>(Capability.class);
    private static String notes = "";

    static {
        for (Capability capability : Capability.values()) {
            STATUSES.put(capability, CapabilityStatus.UNKNOWN);
        }
    }

    private TrainingStore() {
    }

    public static synchronized void load() {
        Path file = getProfilePath();
        if (!Files.exists(file)) {
            return;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
            for (Capability capability : Capability.values()) {
                String value = properties.getProperty("capability." + capability.name(), "UNKNOWN");
                try {
                    STATUSES.put(capability, CapabilityStatus.valueOf(value));
                } catch (IllegalArgumentException ignored) {
                    STATUSES.put(capability, CapabilityStatus.UNKNOWN);
                }
            }
            notes = properties.getProperty("notes", "");
            if (notes.length() > 500) notes = notes.substring(0, 500);
        } catch (IOException exception) {
            HeroBotAiClient.LOGGER.error("Trainingsprofil konnte nicht gelesen werden: {}", file, exception);
            DiagnosticsLog.writeFailure("Trainingsprofil konnte nicht gelesen werden: " + file, exception);
        }
    }

    public static synchronized CapabilityStatus status(Capability capability) {
        return STATUSES.getOrDefault(capability, CapabilityStatus.UNKNOWN);
    }

    public static synchronized String notes() {
        return notes;
    }

    public static synchronized void setStatus(Capability capability, CapabilityStatus status) {
        STATUSES.put(capability, status);
        save(notes);
        DiagnosticsLog.writeEvent("Fähigkeit markiert: " + capability.name() + "=" + status.name());
    }

    public static synchronized void save(String updatedNotes) {
        notes = updatedNotes == null ? "" : updatedNotes.substring(0, Math.min(500, updatedNotes.length()));
        Properties properties = new Properties();
        for (Capability capability : Capability.values()) {
            properties.setProperty("capability." + capability.name(), status(capability).name());
        }
        properties.setProperty("notes", notes);

        Path file = getProfilePath();
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "HeroBot AI Training - lokale Fähigkeitsangaben");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            DiagnosticsLog.writeEvent("Trainingsprofil gespeichert");
        } catch (IOException exception) {
            HeroBotAiClient.LOGGER.error("Trainingsprofil konnte nicht gespeichert werden: {}", file, exception);
            DiagnosticsLog.writeFailure("Trainingsprofil konnte nicht gespeichert werden: " + file, exception);
        }
    }

    public static synchronized String createShareReport() {
        StringWriter report = new StringWriter();
        report.append("HeroBot AI – Trainingsbericht\n");
        report.append("Minecraft: 1.21.11\n");
        report.append("HeroBot installiert: ").append(DiagnosticsLog.isHeroBotLoaded() ? "ja" : "nein").append('\n');
        report.append("Hinweis: Das sind manuelle Angaben, kein bereits trainiertes Modell.\n\n");
        for (Capability capability : Capability.values()) {
            report.append("- ").append(capability.label()).append(": ")
                    .append(status(capability).label()).append('\n');
        }
        report.append("\nBeobachtungen:\n").append(notes.isBlank() ? "(keine)" : notes).append('\n');
        report.append("\n--- Debug-Log (lokal erzeugte Einträge) ---\n");
        report.append(DiagnosticsLog.readLog());
        report.append("\n--- PvP-Kampfdaten (letzte 100 Zeilen) ---\n");
        report.append(TrainingLog.readRecent());
        return report.toString();
    }

    public static Path getProfilePath() {
        return FabricLoader.getInstance().getConfigDir().resolve("herobot-ai-training").resolve("training.properties");
    }
}
