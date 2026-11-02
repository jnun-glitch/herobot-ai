package de.codex.herobotai;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.command.CommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class HeroBotCommands {
    private static final SimpleCommandExceptionType HERO_BOT_REQUIRED = new SimpleCommandExceptionType(
            Text.literal("HeroBot muss auf dem Server installiert sein."));
    private static final SimpleCommandExceptionType PLAYER_REQUIRED = new SimpleCommandExceptionType(
            Text.literal("Führe den Befehl als Spieler aus."));
    private static final SimpleCommandExceptionType INVALID_NAME = new SimpleCommandExceptionType(
            Text.literal("HeroBot-Namen benötigen 3–16 Buchstaben, Zahlen oder Unterstriche."));

    private HeroBotCommands() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> create(String name) {
        return literal(name)
                .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                .then(literal("help").executes(context -> help(context.getSource())))
                .then(literal("spawn")
                        .executes(context -> spawn(context, "PvPTrainer", null, PvPKit.COPY))
                        .then(argument("botName", StringArgumentType.word())
                                .executes(context -> spawn(context,
                                        StringArgumentType.getString(context, "botName"), null, PvPKit.COPY))
                                .then(argument("targetPlayer", StringArgumentType.word())
                                        .executes(context -> spawn(context,
                                                StringArgumentType.getString(context, "botName"),
                                                StringArgumentType.getString(context, "targetPlayer"), PvPKit.COPY))))
                        .then(literal("kit")
                                .then(argument("kitName", StringArgumentType.word())
                                        .executes(context -> spawnWithKit(context,
                                                StringArgumentType.getString(context, "kitName"), "PvPTrainer", null))
                                        .then(argument("botName", StringArgumentType.word())
                                                .executes(context -> spawnWithKit(context,
                                                        StringArgumentType.getString(context, "kitName"),
                                                        StringArgumentType.getString(context, "botName"), null))
                                                .then(argument("targetPlayer", StringArgumentType.word())
                                                        .executes(context -> spawnWithKit(context,
                                                                StringArgumentType.getString(context, "kitName"),
                                                                StringArgumentType.getString(context, "botName"),
                                                                StringArgumentType.getString(context, "targetPlayer"))))))))
                .then(literal("stop").executes(context -> stopAny(context.getSource())))
                .then(literal("remove")
                        .executes(context -> removeActive(context.getSource()))
                        .then(argument("botName", StringArgumentType.word())
                                .executes(context -> removeNamed(context.getSource(),
                                        StringArgumentType.getString(context, "botName")))))
                .then(literal("kit")
                        .executes(context -> listGrantableKits(context.getSource()))
                        .then(argument("first", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(
                                        List.of("iron", "diamond", "axe", "netherite", "mace", "crystal",
                                                "fc_axe", "fc_uhc", "fc_misch"), builder))
                                .executes(context -> grantKit(context,
                                        StringArgumentType.getString(context, "first"), null))
                                .then(argument("second", StringArgumentType.word())
                                        .executes(context -> grantKitFlexible(context,
                                                StringArgumentType.getString(context, "first"),
                                                StringArgumentType.getString(context, "second"))))))
                .then(literal("status").executes(context -> status(context.getSource())))
                .then(literal("kits").executes(context -> listKits(context.getSource())));
    }

    private static int grantKitFlexible(CommandContext<ServerCommandSource> context,
                                        String first, String second)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        PvPKit firstKit = PvPKit.parse(first);
        PvPKit secondKit = PvPKit.parse(second);
        if (isGrantableKit(firstKit)) {
            return grantKit(context, first, second);
        }
        if (isGrantableKit(secondKit)) {
            return grantKit(context, second, first);
        }
        source.sendError(Text.literal("Unbekannte Auswahl. Nutze /hba kit für die verfügbaren Kits."));
        return 0;
    }

    private static int grantKit(CommandContext<ServerCommandSource> context, String kitName, String playerName)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        PvPKit kit = PvPKit.parse(kitName);
        if (!isGrantableKit(kit)) {
            source.sendError(Text.literal("Unbekanntes Kit. Nutze /hba kit für die verfügbaren Kits."));
            return 0;
        }

        ServerPlayerEntity recipient;
        if (playerName == null) {
            recipient = source.getPlayerOrThrow();
        } else {
            if (!isValidName(playerName)) {
                throw INVALID_NAME.create();
            }
            recipient = HeroBotAdapter.findPlayer(source.getServer(), playerName);
            if (recipient == null) {
                source.sendError(Text.literal("Spieler nicht online: " + playerName));
                return 0;
            }
        }

        String recipientName = recipient.getName().getString();
        boolean recipientIsBot = HeroBotAdapter.isHeroBot(recipient);
        for (String command : kit.applyCommands(recipientName)) {
            if (command.equals("clear " + recipientName)
                    && !HeroBotAdapter.hasInventoryItems(recipient)) {
                continue;
            }
            if (!recipientIsBot && command.startsWith("player " + recipientName + " hotbar ")) {
                continue;
            }
            HeroBotAdapter.runRaw(source.getServer(), command);
        }
        recipient.getInventory().markDirty();
        recipient.playerScreenHandler.sendContentUpdates();
        if (recipient.currentScreenHandler != recipient.playerScreenHandler) {
            recipient.currentScreenHandler.sendContentUpdates();
        }
        String kitLabel = kit.commandName();
        TrainingLog.event("KIT_GRANTED kit=" + kitLabel + " recipient=" + recipientName);
        source.sendFeedback(() -> Text.literal("Kit " + kitLabel + " an " + recipientName
                + " gegeben. Das Inventar wurde ersetzt; die Waffe liegt auf Hotbar-Slot 1."), false);
        return 1;
    }

    private static int listGrantableKits(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("Verfügbare Kits: iron, diamond, axe, netherite, mace, crystal, fc_axe, fc_uhc, fc_misch."), false);
        source.sendFeedback(() -> Text.literal("/hba kit <Kit> — Kit an dich geben; optional /hba kit <Kit> <Spieler> (auch umgekehrt)."), false);
        source.sendFeedback(() -> Text.literal("Das Kit ersetzt das Inventar des Empfängers. Mit Tab kannst du Kits auswählen."), false);
        return 1;
    }

    private static boolean isGrantableKit(PvPKit kit) {
        return kit != null && kit != PvPKit.COPY;
    }

    private static int spawn(CommandContext<ServerCommandSource> context, String botName,
                             String targetName, PvPKit kit)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        if (!FabricLoader.getInstance().isModLoaded("herobot")) {
            throw HERO_BOT_REQUIRED.create();
        }
        if (!isValidName(botName)) {
            throw INVALID_NAME.create();
        }

        MinecraftServer server = source.getServer();
        ServerPlayerEntity target;
        if (targetName == null) {
            target = source.getPlayerOrThrow();
        } else {
            if (!isValidName(targetName)) {
                throw INVALID_NAME.create();
            }
            target = HeroBotAdapter.findPlayer(server, targetName);
            if (target == null) {
                source.sendError(Text.literal("Spieler nicht online: " + targetName));
                return 0;
            }
        }

        return TrainingController.spawnAndStart(server, source, botName, target, kit);
    }

    private static int spawnWithKit(CommandContext<ServerCommandSource> context, String kitName,
                                    String botName, String targetName)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        PvPKit kit = PvPKit.parse(kitName);
        if (kit == null) {
            context.getSource().sendError(Text.literal("Unbekanntes Kit. Verfügbare Kits: iron, diamond, axe, netherite, mace, crystal, fc_axe, fc_uhc, fc_misch."));
            return 0;
        }
        return spawn(context, botName, targetName, kit);
    }


    private static int stopAny(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        if (TrainingController.isBusy(server)) {
            return TrainingController.stop(server, source, false);
        }
        source.sendError(Text.literal("Es läuft kein PvP-Training."));
        return 0;
    }

    private static int status(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        return TrainingController.status(server, source);
    }

    private static int removeActive(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        return TrainingController.removeActiveBot(server, source);
    }

    private static int removeNamed(ServerCommandSource source, String botName) {
        if (!isValidName(botName)) {
            source.sendError(Text.literal("Ungültiger Botname. HeroBot benötigt 3–16 Buchstaben, Zahlen oder Unterstriche."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        return TrainingController.removeBot(server, source, botName);
    }

    private static int listKits(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("Kits: copy (dein Inventar), iron, diamond, axe, netherite, mace (Elytra-Flug und Mace-Sturzangriff), crystal (Netherite + End-Crystals + Totem + Obsidian)."), false);
        source.sendFeedback(() -> Text.literal("Deine FireClient-Vorlagen fc_axe, fc_uhc, fc_misch sind exakt nach deinen gespeicherten Kits gebaut."), false);
        source.sendFeedback(() -> Text.literal("Alle Kits nutzen dieselbe Kampf-KI; sie unterscheiden sich nur durch die Startausrüstung. Der Bot kann bessere Rüstung anziehen, Schilde einsetzen und ab 7 Herzen essen."), false);
        source.sendFeedback(() -> Text.literal("Nutze /hba spawn kit <Kit> [BotName] [Gegner]."), false);
        return 1;
    }

    private static int help(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (source.getPlayer() == null) {
            throw PLAYER_REQUIRED.create();
        }
        source.sendFeedback(() -> Text.literal("/hba spawn [BotName] [Gegner] — Bot spawnen und PvP-Training starten"), false);
        source.sendFeedback(() -> Text.literal("/hba status — aktiven Kampf und Lernfortschritt anzeigen"), false);
        source.sendFeedback(() -> Text.literal("/hba stop — Training und Bot-Aktionen anhalten"), false);
        source.sendFeedback(() -> Text.literal("/hba remove [BotName] — Bot entfernen"), false);
        source.sendFeedback(() -> Text.literal("/hba kit [Kit] [Spieler] — Kits ohne Bot vergeben; /hba kit zeigt die Auswahl"), false);
        source.sendFeedback(() -> Text.literal("/hba spawn kit <Kit> [BotName] [Gegner] — Preset-Kit wählen"), false);
        source.sendFeedback(() -> Text.literal("/hba kits — verfügbare Kits anzeigen"), false);
        source.sendFeedback(() -> Text.literal("Nur mit Cheats/Operatorrechten. Standard `spawn` kopiert dein Inventar."), false);
        return 1;
    }

    private static boolean isValidName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{3,16}");
    }
}
