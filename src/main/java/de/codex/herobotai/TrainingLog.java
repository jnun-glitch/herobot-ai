package de.codex.herobotai;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

final class TrainingLog {
    private static final String HEADER = "time,tick,bot,target,state,action,reward,terminal" + System.lineSeparator();
    private static final long MAX_LOG_BYTES = 5L * 1024L * 1024L;

    private TrainingLog() {
    }

    static synchronized void record(long tick, String bot, String target, String state,
                                    QLearningPolicy.Action action, double reward, boolean terminal) {
        append(csv(Instant.now().toString()) + ',' + csv(Long.toString(tick)) + ',' + csv(bot) + ','
                + csv(target) + ',' + csv(state) + ',' + csv(action.name()) + ','
                + csv(String.format(java.util.Locale.ROOT, "%.4f", reward)) + ',' + terminal);
    }

    static synchronized void event(String message) {
        append("# " + Instant.now() + " " + message.replace('\n', ' ').replace('\r', ' '));
    }

    static synchronized void error(String message) {
        event("ERROR " + message);
    }

    static Path getPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("herobot-ai-training").resolve("fight-data.csv");
    }

    static synchronized String readRecent() {
        Path file = getPath();
        if (Files.notExists(file)) {
            return "(Kampfdaten liegen serverseitig unter " + file + ")\n";
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - 100);
            return String.join(System.lineSeparator(), lines.subList(start, lines.size())) + System.lineSeparator();
        } catch (IOException exception) {
            HeroBotAiCommon.LOGGER.error("Could not read fight log {}", file, exception);
            return "(Kampfdaten konnten nicht gelesen werden: " + exception.getClass().getSimpleName() + ")\n";
        }
    }

    private static void append(String line) {
        Path file = getPath();
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) >= MAX_LOG_BYTES) {
                Path previous = file.resolveSibling(file.getFileName() + ".1");
                Files.move(file, previous, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.notExists(file)) {
                Files.writeString(file, HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            HeroBotAiCommon.LOGGER.error("Could not write fight log {}", file, exception);
        }
    }

    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + "\"";
    }
}
