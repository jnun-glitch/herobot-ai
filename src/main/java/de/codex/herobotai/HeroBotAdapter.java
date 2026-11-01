package de.codex.herobotai;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.scoreboard.Team;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;
import java.util.Objects;

final class HeroBotAdapter {
    private static final String BOT_PLAYER_CLASS = "hero.bane.herobot.mod.common.bot.BotPlayer";

    private HeroBotAdapter() {
    }

    static void spawn(MinecraftServer server, String botName, ServerPlayerEntity anchor, Vec3d position) {
        String dimension = anchor.getEntityWorld().getRegistryKey().getValue().toString();
        run(server, "playerspawn " + botName + " at " + coord(position.x) + " " + coord(position.y) + " "
                + coord(position.z) + " facing " + coord(anchor.getYaw()) + " " + coord(anchor.getPitch())
                + " in survival on " + dimension);
    }

    static ServerPlayerEntity findPlayer(MinecraftServer server, String name) {
        if (name == null) return null;
        ServerPlayerEntity exact = server.getPlayerManager().getPlayer(name);
        if (exact != null) return exact;
        return server.getPlayerManager().getPlayerList().stream()
                .filter(player -> Objects.equals(player.getName().getString().toLowerCase(Locale.ROOT),
                        name.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);
    }

    /**
     * smoothingTicks <= 1 bedeutet hier "sofort": der Delta-Teil wird gar nicht gesendet.
     * Belegt am Bytecode von BotPlayerActionPack: eine lookInterpolation feuert erst bei
     * ticksRemaining == 0, also im Folgetick, und look(float,float) bricht sie NICHT ab (nur
     * stopInterpolation() tut das). Ein "delta 1" haette also nur verzögert, ohne zu glätten,
     * und dabei einen direkt anschliessenden Angriff mit dem ALTEN Blick abgesessen — das ist
     * die Ursache fuer die daneben schlagenden Treffer.
     */
    static void lookAtEntity(MinecraftServer server, String bot, String target, int smoothingTicks) {
        if (smoothingTicks <= 1) {
            lookAtEntityNow(server, bot, target);
            return;
        }
        run(server, "player " + bot + " look upon " + target + " delta " + smoothingTicks);
    }

    static void lookAtPosition(MinecraftServer server, String bot, Vec3d position, int smoothingTicks) {
        if (smoothingTicks <= 1) {
            lookAtPositionNow(server, bot, position);
            return;
        }
        run(server, "player " + bot + " look at " + coord(position.x) + " " + coord(position.y) + " "
                + coord(position.z) + " delta " + smoothingTicks);
    }

    static void lookAtEntityNow(MinecraftServer server, String bot, String target) {
        run(server, "player " + bot + " look upon " + target);
    }

    /** Sofortiger Blick auf eine Position, ohne Interpolation. */
    static void lookAtPositionNow(MinecraftServer server, String bot, Vec3d position) {
        run(server, "player " + bot + " look at " + coord(position.x) + " " + coord(position.y)
                + " " + coord(position.z));
    }

    static void lookLeft(MinecraftServer server, String bot, int smoothingTicks) {
        run(server, "player " + bot + " look left delta " + smoothingTicks);
    }

    static void move(MinecraftServer server, String bot, String direction) {
        run(server, "player " + bot + " move " + direction);
    }

    static void stopMovement(MinecraftServer server, String bot) {
        run(server, "player " + bot + " move");
    }

    static void stopAll(MinecraftServer server, String bot) {
        run(server, "player " + bot + " stop");
    }

    static void setSprint(MinecraftServer server, String bot, boolean enabled) {
        run(server, "player " + bot + (enabled ? " sprint" : " unsprint"));
    }

    static void setAutoJump(MinecraftServer server, String bot, boolean enabled) {
        run(server, "player " + bot + " autojump " + enabled);
    }

    static void jump(MinecraftServer server, String bot) {
        run(server, "player " + bot + " jump once");
    }

    static void setAttacking(MinecraftServer server, String bot, boolean enabled) {
        run(server, "player " + bot + (enabled ? " attack continuous" : " attack"));
    }

    static void attackOnce(MinecraftServer server, String bot) {
        run(server, "player " + bot + " attack once");
    }

    static void attackTwice(MinecraftServer server, String bot) {
        run(server, "player " + bot + " attack twice");
    }

    /** Setzt den Item-Cooldown (z. B. ender_pearl), ohne das Item zu benutzen. */
    static void resetItemCooldown(MinecraftServer server, String bot, String itemId) {
        run(server, "player " + bot + " itemCd " + itemId + " reset");
    }

    /** Sichert die aktuellen Serverwerte und setzt die Trainingswerte temporär. */
    static CombatSettingsSnapshot applyCombatSettings(MinecraftServer server) {
        CombatSettingsSnapshot previous = readCombatSettings();
        if (previous == null) {
            HeroBotAiCommon.LOGGER.warn("HeroBot-Kampfeinstellungen konnten nicht gesichert werden; lasse sie unverändert.");
            TrainingLog.error("HeroBot-Kampfeinstellungen nicht gelesen; keine globalen Werte geändert");
            return null;
        }
        run(server, "herobot shieldStunning true");
        run(server, "herobot noProjectileRandom true");
        return previous;
    }

    /** Stellt die Einstellungen wieder her, die vor dem Trainingskampf aktiv waren. */
    static void restoreCombatSettings(MinecraftServer server, CombatSettingsSnapshot previous) {
        if (previous == null) return;
        run(server, "herobot shieldStunning " + previous.shieldStunning());
        run(server, "herobot noProjectileRandom " + previous.noProjectileRandom());
    }

    private static CombatSettingsSnapshot readCombatSettings() {
        try {
            Class<?> settings;
            try {
                settings = Class.forName("hero.bane.herobot.common.rule.HeroBotSettings", false,
                        HeroBotAdapter.class.getClassLoader());
            } catch (ClassNotFoundException isolatedModClassLoader) {
                settings = Class.forName("hero.bane.herobot.common.rule.HeroBotSettings", false,
                        Thread.currentThread().getContextClassLoader());
            }
            boolean shieldStunning = settings.getField("shieldStunning").getBoolean(null);
            boolean noProjectileRandom = settings.getField("noProjectileRandom").getBoolean(null);
            return new CombatSettingsSnapshot(shieldStunning, noProjectileRandom);
        } catch (ReflectiveOperationException | LinkageError exception) {
            HeroBotAiCommon.LOGGER.error("Could not read current HeroBot combat settings", exception);
            TrainingLog.error("Could not read HeroBot combat settings: " + exception);
            return null;
        }
    }

    static boolean supportsPathing(MinecraftServer server) {
        var playerCommand = server.getCommandManager().getDispatcher().getRoot().getChild("player");
        return playerCommand != null && playerCommand.getChild("path") != null;
    }

    static void pathToEntity(MinecraftServer server, String bot, String target) {
        run(server, "player " + bot + " path entity " + target);
    }

    static void stopPathing(MinecraftServer server, String bot) {
        run(server, "player " + bot + " path stop");
    }

    /** Nutzt den Gegenstand im gewaehlten Slot einmal. */
    static void useOnce(MinecraftServer server, String bot) {
        run(server, "player " + bot + " use once");
    }

    static void copyInventory(ServerPlayerEntity bot, ServerPlayerEntity source) {
        for (int slot = 0; slot < 36; slot++) {
            bot.getInventory().setStack(slot, source.getInventory().getStack(slot).copy());
        }
        bot.equipStack(EquipmentSlot.HEAD, source.getEquippedStack(EquipmentSlot.HEAD).copy());
        bot.equipStack(EquipmentSlot.CHEST, source.getEquippedStack(EquipmentSlot.CHEST).copy());
        bot.equipStack(EquipmentSlot.LEGS, source.getEquippedStack(EquipmentSlot.LEGS).copy());
        bot.equipStack(EquipmentSlot.FEET, source.getEquippedStack(EquipmentSlot.FEET).copy());
        bot.equipStack(EquipmentSlot.OFFHAND, source.getOffHandStack().copy());
    }

    static void copyTeam(MinecraftServer server, ServerPlayerEntity owner, String bot) {
        Team team = owner.getScoreboardTeam();
        if (team != null) {
            server.getScoreboard().addScoreHolderToTeam(bot, team);
        }
    }

    static void runRaw(MinecraftServer server, String command) {
        run(server, command);
    }

    static void setUsing(MinecraftServer server, String bot, boolean enabled) {
        run(server, "player " + bot + (enabled ? " use continuous" : " use"));
    }

    static void selectHotbar(MinecraftServer server, String bot, int zeroBasedSlot) {
        if (zeroBasedSlot >= 0 && zeroBasedSlot < 9) {
            run(server, "player " + bot + " hotbar " + (zeroBasedSlot + 1));
        }
    }

    static void disconnect(MinecraftServer server, String bot) {
        run(server, "player " + bot + " disconnect");
    }

    static void teleport(MinecraftServer server, String bot, Vec3d position, float yaw, float pitch) {
        run(server, "tp " + bot + " " + coord(position.x) + " " + coord(position.y) + " " + coord(position.z)
                + " " + coord(yaw) + " " + coord(pitch));
    }

    static boolean isHeroBot(ServerPlayerEntity player) {
        if (!FabricLoader.getInstance().isModLoaded("herobot")) {
            return false;
        }
        if (player.getClass().getName().equals(BOT_PLAYER_CLASS)) {
            return true;
        }
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            return Class.forName(BOT_PLAYER_CLASS, false, loader).isInstance(player);
        } catch (ClassNotFoundException | LinkageError exception) {
            return player.getClass().getSimpleName().equals("BotPlayer");
        }
    }

    /**
     * Fuehrt einen HeroBot-Befehl aus und protokolliert Syntaxfehler.
     * parseAndExecute() faengt Syntaxfehler intern (nur roter Chat-Fehler), deshalb wird der
     * Brigadier-Dispatcher direkt genutzt: execute(String, S) wirft eine CommandSyntaxException.
     */
    private static void run(MinecraftServer server, String command) {
        ServerCommandSource source = server.getCommandSource().withSilent();
        try {
            server.getCommandManager().getDispatcher().execute(command, source);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException syntaxError) {
            HeroBotAiCommon.LOGGER.error("HeroBot-Syntax abgelehnt: {} :: {}", command, syntaxError.getMessage());
            TrainingLog.error("HeroBot-Syntax abgelehnt: " + command + " :: " + syntaxError.getMessage());
        } catch (Throwable exception) {
            HeroBotAiCommon.LOGGER.error("HeroBot-Aktion fehlgeschlagen: {}", command, exception);
            TrainingLog.error("Arena-Adapter: " + command + " :: " + exception);
        }
    }

    private static String coord(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    record CombatSettingsSnapshot(boolean shieldStunning, boolean noProjectileRandom) {
    }
}
