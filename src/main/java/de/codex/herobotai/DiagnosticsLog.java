package de.codex.herobotai;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

public final class DiagnosticsLog {
    private static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_REPORT_LINES = 200;

    private DiagnosticsLog() {
    }

    public static boolean isHeroBotLoaded() {
        return FabricLoader.getInstance().isModLoaded("herobot");
    }

    public static Path getLogPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("herobot-ai-training").resolve("debug.log");
    }

    public static synchronized void writeEvent(String event) {
        append("EVENT " + clean(event));
    }

    public static synchronized void writeFailure(String message, Throwable error) {
        StringWriter details = new StringWriter();
        error.printStackTrace(new PrintWriter(details));
        append("ERROR " + clean(message) + System.lineSeparator() + details.toString().stripTrailing());
    }

    public static synchronized void captureSnapshot() {
        MinecraftClient client = MinecraftClient.getInstance();
        StringBuilder snapshot = new StringBuilder("SNAPSHOT\n");
        snapshot.append("minecraft=1.21.11\n");
        snapshot.append("herobot_loaded=").append(isHeroBotLoaded()).append('\n');

        if (client.world == null || client.player == null) {
            snapshot.append("world_loaded=false\n");
            snapshot.append("player_loaded=false\n");
        } else {
            snapshot.append("world_loaded=true\n");
            snapshot.append("player_loaded=true\n");
            snapshot.append("dimension=").append(client.world.getRegistryKey().getValue()).append('\n');
            snapshot.append("position=")
                    .append(round(client.player.getX())).append(',')
                    .append(round(client.player.getY())).append(',')
                    .append(round(client.player.getZ())).append('\n');
            snapshot.append("rotation=")
                    .append(round(client.player.getYaw())).append(',')
                    .append(round(client.player.getPitch())).append('\n');
            snapshot.append("on_ground=").append(client.player.isOnGround()).append('\n');
            snapshot.append("health=")
                    .append(round(client.player.getHealth())).append('/')
                    .append(round(client.player.getMaxHealth())).append('\n');
            snapshot.append("held_item=").append(clean(client.player.getMainHandStack().getName().getString())).append('\n');
            snapshot.append("crosshair=").append(client.crosshairTarget == null
                    ? "none" : client.crosshairTarget.getType().name()).append('\n');

            HitResult hit = client.crosshairTarget;
            if (hit instanceof EntityHitResult entityHit) {
                Entity target = entityHit.getEntity();
                snapshot.append("target_type=").append(target.getType()).append('\n');
                snapshot.append("target_name=").append(clean(target.getName().getString())).append('\n');
                snapshot.append("target_distance=").append(round(client.player.distanceTo(target))).append('\n');
            } else if (hit instanceof BlockHitResult blockHit) {
                snapshot.append("target_block=").append(blockHit.getBlockPos()).append('\n');
            }
        }

        append(snapshot.toString().stripTrailing());
    }

    public static synchronized String readLog() {
        Path file = getLogPath();
        if (!Files.exists(file)) {
            return "(Noch keine Debug-Einträge. Erzeuge zuerst einen Snapshot.)\n";
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - MAX_REPORT_LINES);
            String recent = String.join(System.lineSeparator(), lines.subList(start, lines.size())) + System.lineSeparator();
            return recent.length() > 65_536 ? recent.substring(recent.length() - 65_536) : recent;
        } catch (IOException exception) {
            HeroBotAiClient.LOGGER.error("Debug-Log konnte nicht gelesen werden: {}", file, exception);
            return "(Debug-Log konnte nicht gelesen werden: " + exception.getClass().getSimpleName() + ")\n";
        }
    }

    private static void append(String message) {
        Path file = getLogPath();
        String line = Instant.now() + " " + message + System.lineSeparator();
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) >= MAX_LOG_BYTES) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            HeroBotAiClient.LOGGER.error("Debug-Log konnte nicht geschrieben werden: {}", file, exception);
        }
    }

    private static String clean(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
