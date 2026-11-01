package de.codex.herobotai;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

final class QLearningPolicy {
    enum Action {
        APPROACH,
        STRAFE_LEFT,
        STRAFE_RIGHT,
        RETREAT,
        HOLD
    }

    private static final double LEARNING_RATE = 0.18;
    private static final double DISCOUNT = 0.95;
    private static final double EXPLORATION_START = 0.18;
    private static final double EXPLORATION_MIN = 0.03;
    private static final double EXPLORATION_DECAY_UPDATES = 1500.0;
    private final Map<String, double[]> values = new HashMap<>();
    private long updates;

    QLearningPolicy() {
        load();
    }

    String observe(double distance, float botHealth, float targetHealth,
                   boolean targetOnGround, boolean targetBlocking, boolean axeAvailable) {
        int distanceBucket = distance < 2.2 ? 0 : distance < 4.0 ? 1 : distance < 8.0 ? 2 : 3;
        float healthDifference = botHealth - targetHealth;
        int healthBucket = healthDifference < -3.0 ? 0 : healthDifference > 3.0 ? 2 : 1;
        int ownHealthBucket = botHealth <= 8.0F ? 0 : botHealth <= 16.0F ? 1 : 2;
        return "d" + distanceBucket + "_h" + healthBucket + "_air" + (targetOnGround ? 0 : 1)
                + "_b" + (targetBlocking ? 1 : 0) + "_o" + ownHealthBucket
                + "_x" + (axeAvailable ? 1 : 0);
    }

    Action choose(String state) {
        double explorationRate = EXPLORATION_MIN + (EXPLORATION_START - EXPLORATION_MIN)
                * Math.exp(-updates / EXPLORATION_DECAY_UPDATES);
        return choose(state, explorationRate);
    }

    Action choose(String state, double explorationRate) {
        double rate = Double.isFinite(explorationRate)
                ? Math.max(0.0, Math.min(1.0, explorationRate)) : EXPLORATION_START;
        if (ThreadLocalRandom.current().nextDouble() < rate) {
            Action[] actions = Action.values();
            return actions[ThreadLocalRandom.current().nextInt(actions.length)];
        }

        double[] q = valuesFor(state);
        double best = Double.NEGATIVE_INFINITY;
        List<Action> ties = new ArrayList<>();
        for (Action action : Action.values()) {
            double value = q[action.ordinal()];
            if (value > best + 1.0E-9) {
                best = value;
                ties.clear();
                ties.add(action);
            } else if (Math.abs(value - best) <= 1.0E-9) {
                ties.add(action);
            }
        }
        return ties.isEmpty() ? Action.HOLD : ties.get(ThreadLocalRandom.current().nextInt(ties.size()));
    }

    void update(String state, Action action, double reward, String nextState, boolean terminal) {
        if (!Double.isFinite(reward)) reward = 0.0;
        double[] current = valuesFor(state);
        double future = 0.0;
        if (!terminal && nextState != null) {
            double[] next = valuesFor(nextState);
            for (double value : next) {
                if (Double.isFinite(value)) future = Math.max(future, value);
            }
        }
        int index = action.ordinal();
        if (!Double.isFinite(current[index])) current[index] = 0.0;
        current[index] += LEARNING_RATE * (reward + (terminal ? 0.0 : DISCOUNT * future) - current[index]);
        updates++;
    }

    long updates() {
        return updates;
    }

    int stateCount() {
        return values.size();
    }

    void save() {
        Properties properties = new Properties();
        properties.setProperty("updates", Long.toString(updates));
        values.forEach((state, q) -> {
            for (Action action : Action.values()) {
                properties.setProperty("q." + state + "." + action.name(), Double.toString(q[action.ordinal()]));
            }
        });

        Path file = getPath();
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "HeroBot AI tabular Q-learning model");
            }
            try {
                Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            HeroBotAiCommon.LOGGER.error("Could not save Q-learning table to {}", file, exception);
            TrainingLog.error("Could not save Q-learning table: " + exception);
        }
    }

    private void load() {
        Path file = getPath();
        if (!Files.exists(file)) {
            return;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
            updates = Math.max(0L, Long.parseLong(properties.getProperty("updates", "0")));
            for (String key : properties.stringPropertyNames()) {
                if (!key.startsWith("q.")) {
                    continue;
                }
                String[] parts = key.split("\\.");
                if (parts.length != 3) {
                    continue;
                }
                try {
                    Action action = Action.valueOf(parts[2]);
                    double value = Double.parseDouble(properties.getProperty(key));
                    if (Double.isFinite(value)) {
                        valuesFor(parts[1])[action.ordinal()] = value;
                    }
                } catch (IllegalArgumentException ignored) {
                    // Skip values written by an incompatible future model version.
                }
            }
        } catch (IOException | NumberFormatException exception) {
            HeroBotAiCommon.LOGGER.error("Could not load Q-learning table from {}", file, exception);
            TrainingLog.error("Could not load Q-learning table: " + exception);
        }
    }

    private double[] valuesFor(String state) {
        return values.computeIfAbsent(state, ignored -> new double[Action.values().length]);
    }

    private static Path getPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("herobot-ai-training").resolve("qtable.properties");
    }
}
