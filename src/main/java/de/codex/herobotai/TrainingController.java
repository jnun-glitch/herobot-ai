package de.codex.herobotai;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;
import java.util.Set;

final class TrainingController {
    private static final int DECISION_INTERVAL_TICKS = 5;
    private static final double MELEE_DISTANCE = 2.45;
    private static final double MANUAL_ATTACK_DISTANCE = 2.75;
    /** Mindester-Angriffs-Abstand in Ticks (W-Tap-Grundrhythmus). Waffen-Cooldowns prüft weaponCooldownTicks(). */
    private static final long MANUAL_ATTACK_INTERVAL_TICKS = 9;
    /** Nach jedem Treffer Sprint 2 Ticks loslassen -> jeder Hit wird Sprint-KB-Angriff (W-Tap). */
    private static final long W_TAP_RELEASE_TICKS = 2;
    /**
     * Ticks zwischen Sprung und Beginn der fallenden Phase. Belegt: getJumpVelocity()
     * liefert fuer Spieler 0.42, die Schwerkraft zieht pro Tick 0.08 ab
     * (0.42 - 0.08 * 5 = 0.02, also noch aufsteigend; ab Tick 6 wird y negativ).
     * Vanillas isCriticalHit() verlangt zusaetzlich fallDistance > 0 — der Schlag darf
     * also NICHT in der aufsteigenden Phase landen.
     */
    private static final int CRIT_FALL_DELAY_TICKS = 6;
    /** Fenster nach Beginn der fallenden Phase, in dem der Crit noch landen darf. */
    private static final int CRIT_FALL_DEADLINE_TICKS = 5;
    /** Sentinel: kein Crit-Angriff geplant. */
    private static final long NO_PENDING_CRIT = Long.MIN_VALUE / 2;
    private static final double PEARL_DISTANCE = 14.0;
    private static final long PEARL_COOLDOWN_TICKS = 60;
    private static final float HEAL_HEALTH_THRESHOLD = 14.0F;
    private static final long EAT_DURATION_TICKS = 40;
    private static final long EAT_COOLDOWN_TICKS = 100;
    private static final float HUNGER_EAT_THRESHOLD = 18.0F;
    private static final float RETREAT_HEALTH_THRESHOLD = 8.0F;
    private static final float RETREAT_END_HEALTH = 14.0F;
    /** Zielpunkt des Rueckzugs liegt so viele Blöcke hinter dem Bot. */
    private static final double RETREAT_FLEE_DISTANCE = 12.0;
    private static final double EAT_SAFE_DISTANCE = 3.5;
    private static final long SHIELD_RETRIGGER_COOLDOWN_TICKS = 40;
    private static final long SHIELD_MAX_HOLD_TICKS = 40;
    private static final double CRYSTAL_MIN_DISTANCE = 3.0;
    private static final double CRYSTAL_MAX_DISTANCE = 6.0;
    private static final float CRYSTAL_MIN_HEALTH = 12.0F;
    private static final long CRYSTAL_COOLDOWN_TICKS = 50;
    private static final double RANGED_MIN_DISTANCE = 8.0;
    private static final double RANGED_MAX_DISTANCE = 28.0;
    private static final double RANGED_STOP_DISTANCE = 5.0;
    private static final long WIND_CHARGE_COOLDOWN_TICKS = 40;
    private static final long SPLASH_BUFF_COOLDOWN_TICKS = 100;
    /** Splash-Potion of Damage: nur aus der Naehe auf den Gegner werfen, mit eigenem Cooldown. */
    private static final long SPLASH_DAMAGE_COOLDOWN_TICKS = 80;
    private static final double SPLASH_DAMAGE_MAX_DISTANCE = 6.0;
    private static final double HUMAN_WIN_REWARD = 12.0;
    private static final double HUMAN_MIN_MOVE = 0.15;
    private static final double HUMAN_FORWARD_STEP = 0.15;
    private static final double HUMAN_SIDE_STEP = 0.10;
    private static final Set<String> FOOD_PATHS = Set.of(
            "bread", "cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton",
            "cooked_rabbit", "cooked_cod", "cooked_salmon", "baked_potato", "golden_carrot",
            "melon_slice", "apple", "cookie", "pumpkin_pie", "mushroom_stew", "beetroot_soup");
    private static final long HEAL_DURATION_TICKS = 44;
    private static final long HEAL_COOLDOWN_TICKS = 120;
    private static final long SPAWN_TIMEOUT_TICKS = 400;
    private static final int MACE_LAUNCH_HEIGHT = 14;
    private static final int MACE_CLIMB_HEIGHT = 20;
    private static final int MACE_MAX_CLIMB_TICKS = 45;
    private static final int MACE_DIVE_TIMEOUT_TICKS = 80;
    /** Pause nach abgeschlossenem Mace-Opener, bevor der naechste Flug anlaeuft. */
    private static final int MACE_OPENER_COOLDOWN_TICKS = 200;
    private static Session active;
    private static PendingStart pending;

    private TrainingController() {
    }

    static int spawnAndStart(MinecraftServer server, ServerCommandSource source,
                             String botName, ServerPlayerEntity target, PvPKit kit) {
        if (isBusy(server)) {
            source.sendError(Text.literal("Ein Trainingskampf läuft bereits. Nutze /hba stop oder /hba remove."));
            return 0;
        }
        if (HeroBotAdapter.findPlayer(server, botName) != null) {
            source.sendError(Text.literal("Der Name ist bereits belegt. Nutze /hba remove " + botName + " oder einen anderen Namen."));
            return 0;
        }

        String targetName = target.getName().getString();
        if (botName.equalsIgnoreCase(targetName)) {
            source.sendError(Text.literal("Der Bot und der Gegner müssen unterschiedliche Spieler sein."));
            return 0;
        }
        if (target.isCreative()) {
            source.sendFeedback(() -> Text.literal("Hinweis: " + targetName
                    + " ist im Kreativmodus und kann nicht normal getroffen werden. Wechsle vor dem Training mit /gamemode survival in den Überlebensmodus."), false);
        }

        String dimension = target.getEntityWorld().getRegistryKey().getValue().toString();
        String spawn = "playerspawn " + botName
                + " at " + coordinate(target.getX() + 1.5) + " " + coordinate(target.getY()) + " " + coordinate(target.getZ())
                + " facing " + coordinate(target.getYaw()) + " " + coordinate(target.getPitch())
                + " in survival on " + dimension;
        execute(server, spawn);
        pending = new PendingStart(server, source, botName, targetName, kit);
        TrainingLog.event("SPAWN_REQUEST bot=" + botName + " target=" + targetName + " mode=duel");
        source.sendFeedback(() -> Text.literal("HeroBot-Spawn angefordert für " + botName
                + ". Das Training beginnt automatisch, sobald der Fake-Spieler beigetreten ist."), false);
        return 1;
    }

    private static void startTraining(PendingStart request, ServerPlayerEntity bot) {
        MinecraftServer server = request.server;
        ServerCommandSource source = request.source;
        String botName = bot.getName().getString();
        String targetName = request.targetName;
        PvPKit kit = request.kit;
        ServerPlayerEntity target = HeroBotAdapter.findPlayer(server, targetName);
        if (target == null) {
            HeroBotAdapter.disconnect(server, botName);
            source.sendError(Text.literal("Der Gegner ist nicht mehr online. Der HeroBot wurde entfernt."));
            TrainingLog.error("Training start cancelled: target left before bot joined target=" + targetName);
            return;
        }
        if (bot.getEntityWorld() != target.getEntityWorld()) {
            HeroBotAdapter.disconnect(server, botName);
            source.sendError(Text.literal("Bot und Gegner sind nicht mehr in derselben Dimension."));
            TrainingLog.error("Training start cancelled: different dimensions bot=" + botName + " target=" + targetName);
            return;
        }

        HeroBotAdapter.stopAll(server, botName);
        // Wiki-Einstellungen einmalig pro Kampf: Schild-Stunning (Herobot shieldStunning)
        // und
        // perfektreichweite Projektile (Bogen/Armbrust ohne Streuung).
        HeroBotAdapter.applyCombatSettings(server);
        TrainingLog.event("COMBAT_SETTINGS bot=" + botName + " shieldStunning=true noProjectileRandom=true");
        if (kit == PvPKit.COPY) {
            HeroBotAdapter.copyInventory(bot, target);
            HeroBotAdapter.selectHotbar(server, botName, target.getInventory().getSelectedSlot());
            TrainingLog.event("INVENTORY_COPY bot=" + botName + " source=" + targetName + " action_pack=false");
        } else {
            for (String command : kit.applyCommands(botName)) {
                execute(server, command);
            }
        }
        HeroBotAdapter.stopAll(server, botName);
        if (!kit.isReady(bot)) {
            execute(server, "player " + botName + " disconnect");
            source.sendError(Text.literal("Das Kit " + kit.commandName() + " konnte nicht ausgerüstet werden. Prüfe HeroBot-Serverlogs."));
            TrainingLog.error("Duel kit setup failed bot=" + botName + " kit=" + kit.commandName());
            return;
        }
        QLearningPolicy policy = new QLearningPolicy();
        active = new Session(server, botName, targetName, policy, kit);
        Session startedSession = active;
        if (hasMaceFlightLoadout(bot)) {
            startedSession.maceLoadout = true;
            beginMaceOpener(startedSession, bot, target);
        } else {
            execute(server, "player " + botName + " autojump true");
            // KEIN "attack continuous": HeroBot wuerde so oft schlagen, wie Vanilla erlaubt,
            // und dabei den Attack-Strength-Meter dauernd zuruecksetzen. tryCritMelee waere
            // dann immer mit leerem Meter dran — belegt an target_damage=1.14 statt 8-12.
            // Der kontrollierte Angriff kommt ausschliesslich aus tryCritMelee.
        }
        TrainingLog.event("START bot=" + botName + " target=" + targetName + " kit=" + kit.commandName()
                + " mode=q-learning-combat");
        source.sendFeedback(() -> Text.literal("PvP-Training gestartet: " + botName + " gegen " + targetName
                + " · Kit: " + kit.commandName() + (startedSession.maceLoadout
                ? ". Elytra-Mace-Startmanöver läuft, danach kämpft und lernt der Bot normal weiter."
                : ". Er greift an und lernt Bewegungen.")), false);
        source.sendFeedback(() -> Text.literal("Stoppen: /hba stop · Entfernen: /hba remove"), false);
    }

    static boolean isActive(MinecraftServer server) {
        return active != null && active.server == server;
    }

    static boolean isBusy(MinecraftServer server) {
        return isActive(server) || (pending != null && pending.server == server);
    }

    static void tick(MinecraftServer server) {
        PendingStart request = pending;
        if (request != null && request.server == server) {
            request.ticks++;
            ServerPlayerEntity spawnedBot = HeroBotAdapter.findPlayer(server, request.botName);
            if (request.cancelled) {
                if (spawnedBot != null) {
                    HeroBotAdapter.disconnect(server, request.botName);
                    pending = null;
                    TrainingLog.event("SPAWN_CANCELLED bot=" + request.botName + " late_join=true");
                } else if (request.ticks >= SPAWN_TIMEOUT_TICKS) {
                    pending = null;
                }
                return;
            }
            if (spawnedBot != null) {
                pending = null;
                TrainingLog.event("SPAWN_READY bot=" + request.botName + " ticks=" + request.ticks);
                startTraining(request, spawnedBot);
                return;
            }
            if (request.ticks >= SPAWN_TIMEOUT_TICKS) {
                pending = null;
                HeroBotAdapter.disconnect(server, request.botName);
                request.source.sendError(Text.literal("HeroBot hat " + request.botName
                        + " innerhalb von 20 Sekunden nicht hinzugefügt. Prüfe HeroBot-Serverlog, Profilname und allowSpawningOfflinePlayers."));
                TrainingLog.error("HeroBot spawn timed out bot=" + request.botName + " target=" + request.targetName);
            }
            return;
        }
        Session session = active;
        if (session == null || session.server != server) {
            return;
        }
        session.tick++;
        ServerPlayerEntity bot = HeroBotAdapter.findPlayer(server, session.botName);
        ServerPlayerEntity target = HeroBotAdapter.findPlayer(server, session.targetName);
        if (bot == null || target == null) {
            finish(session, false, "Spieler nicht mehr online");
            return;
        }
        if (bot.getEntityWorld() != target.getEntityWorld()) {
            finish(session, false, "Bot und Gegner sind nicht mehr in derselben Dimension");
            return;
        }

        maybeRestartMaceOpener(session, bot, target);
        // Verschobene Slot-Restauration: erst jetzt, also einen Tick nach der Aktion.
        applyPendingRestores(server, session, bot);
        if (session.macePhase != MacePhase.DONE) {
            tickMaceOpener(session, bot, target);
            return;
        }
        // Crystal-Phasen laufen unabhaengig vom Decision-Intervall weiter.
        tickCrystalBlast(server, session, bot, target);
        // Crit-Nachschlag laeuft ebenfalls jeden Tick: die fallende Phase des Sprungs
        // dauert nur ~6 Ticks, das 5-Tick-Intervall waere sonst nie getroffen.
        tickCritAttack(server, session, bot, target);
        if (session.tick % DECISION_INTERVAL_TICKS != 0) {
            return;
        }

        String state = session.policy.observe(bot.distanceTo(target), bot.getHealth(), target.getHealth(),
                target.isOnGround(), isBlocking(target), hasAxe(bot));
        double botDamage = session.lastBotHealth - bot.getHealth();
        double targetDamage = session.lastTargetHealth - target.getHealth();
        double reward = targetDamage - 1.2 * botDamage;
        if (botDamage > 0.0 || targetDamage > 0.0) {
            // Armor explizit mitloggen: der gemessene Schaden ist NACH Ruestung. Volle
            // Netherit-Ruestung (20 Punkte) reduziert um 80 %, ein voller Treffer mit dem
            // Netherit-Schwert (Basis 11) landet dann als 2.11 statt 11 im Log. Ohne diesen
            // Wert lassen sich Trefferfaehigkeit und Timing nicht voneinander trennen.
            TrainingLog.event("DUEL_DAMAGE bot=" + session.botName + " target=" + session.targetName
                    + " bot_damage=" + String.format(Locale.ROOT, "%.2f", botDamage)
                    + " target_damage=" + String.format(Locale.ROOT, "%.2f", targetDamage)
                    + " bot_armor=" + bot.getArmor() + " target_armor=" + target.getArmor()
                    + " target_creative=" + target.isCreative());
        }
        if (botDamage > 0.0) {
            session.lastBotDamageTick = session.tick;
        }
        if (targetDamage > 0.0) {
            session.lastTargetHitTick = session.tick;
        }
        boolean botDown = !bot.isAlive() || bot.getHealth() <= 0.0F;
        boolean targetDown = !target.isAlive() || target.getHealth() <= 0.0F;

        if (botDown || targetDown) {
            double terminalReward = targetDown && !botDown ? 12.0 : botDown && !targetDown ? -12.0 : 0.0;
            reward += terminalReward;
            if (session.previousState != null) {
                session.policy.update(session.previousState, session.previousAction, reward, null, true);
            }
            learnFromHuman(session, bot, target,
                    botDamage + (botDown && !targetDown ? HUMAN_WIN_REWARD : 0.0), true);
            TrainingLog.record(session.tick, session.botName, session.targetName, state,
                    session.previousAction == null ? QLearningPolicy.Action.HOLD : session.previousAction,
                    reward, true);
            session.policy.save();
            finish(session, false, targetDown && !botDown ? "Sieg" : botDown && !targetDown ? "Niederlage" : "Unentschieden");
            return;
        }

        if (session.previousState != null) {
            session.policy.update(session.previousState, session.previousAction, reward, state, false);
        }
        learnFromHuman(session, bot, target, botDamage, false);

        if (session.healing) {
            if (session.tick >= session.healingUntilTick) {
                finishHealing(server, session, bot);
            } else {
                recordHealingTick(session, bot, target, state, reward);
                return;
            }
        }
        if (bot.getHealth() <= HEAL_HEALTH_THRESHOLD
                && session.tick - session.lastHealTick >= HEAL_COOLDOWN_TICKS
                && (bot.getHealth() <= RETREAT_HEALTH_THRESHOLD || !enemyPressure(bot, target))
                && startHealing(server, session, bot)) {
            recordHealingTick(session, bot, target, state, reward);
            return;
        }
        if (bot.getHealth() > HEAL_HEALTH_THRESHOLD
                && bot.getHungerManager().getFoodLevel() < HUNGER_EAT_THRESHOLD
                && session.tick - session.lastEatTick >= EAT_COOLDOWN_TICKS
                && !enemyPressure(bot, target)
                && startEating(server, session, bot)) {
            recordHealingTick(session, bot, target, state, reward);
            return;
        }

        optimizeArmor(session, bot);
        double distance = bot.distanceTo(target);
        // Fernkampf: solange Ziel zu weit für die Nahkampfwaffe ist, werden Bogen/Armbrust
        // gehalten und geschossen (Wiki: use continuous = jeden Tick halten).
        boolean rangedActive = tryRangedCombat(server, session, bot, target, distance);
        if (!rangedActive) {
            int weaponSlot = chooseWeaponSlot(bot, target);
            if (weaponSlot >= 0 && bot.getInventory().getSelectedSlot() != weaponSlot) {
                HeroBotAdapter.selectHotbar(server, session.botName, weaponSlot);
                TrainingLog.event("WEAPON_SWITCH bot=" + session.botName + " slot=" + weaponSlot
                        + " item=" + itemPath(bot.getInventory().getStack(weaponSlot)));
            }
        }
        updateShield(session, bot, target);
        if (!rangedActive && !session.retreating) {
            trySplashBuff(server, session, bot);
            trySplashDamage(server, session, bot, target);
        }

        QLearningPolicy.Action action = session.policy.choose(state);
        boolean targetBlocking = isBlocking(target);
        if (bot.getHealth() <= RETREAT_HEALTH_THRESHOLD) {
            if (!session.retreating) {
                session.retreating = true;
                TrainingLog.event("RETREAT_START bot=" + session.botName + " health="
                        + String.format(Locale.ROOT, "%.1f", bot.getHealth()));
            }
        } else if (session.retreating
                && (bot.getHealth() >= RETREAT_END_HEALTH || distance >= 8.0)) {
            session.retreating = false;
            TrainingLog.event("RETREAT_END bot=" + session.botName + " health="
                    + String.format(Locale.ROOT, "%.1f", bot.getHealth())
                    + " distance=" + String.format(Locale.ROOT, "%.2f", distance));
        }
        if (session.retreating) {
            action = QLearningPolicy.Action.RETREAT;
        } else if (!bot.canSee(target) && distance <= 6.0) {
            action = session.tick % 2 == 0 ? QLearningPolicy.Action.STRAFE_LEFT
                    : QLearningPolicy.Action.STRAFE_RIGHT;
            if (session.tick - session.lastCombatDiagnosticTick >= 40) {
                TrainingLog.event("COMBAT_OCCLUDED bot=" + session.botName + " target=" + session.targetName
                        + " distance=" + String.format(Locale.ROOT, "%.2f", distance));
                session.lastCombatDiagnosticTick = session.tick;
            }
        } else if (targetBlocking && distance <= 4.5) {
            action = session.tick % 2 == 0 ? QLearningPolicy.Action.STRAFE_LEFT
                    : QLearningPolicy.Action.STRAFE_RIGHT;
        } else if (distance > MELEE_DISTANCE) {
            action = QLearningPolicy.Action.APPROACH;
        }
        if (rangedActive && !session.retreating) {
            // Schießend nicht ins Messer laufen: stehen bleiben und zielen.
            action = distance > RANGED_MAX_DISTANCE
                    ? QLearningPolicy.Action.APPROACH
                    : QLearningPolicy.Action.HOLD;
        }
        tryPearl(server, session, bot, target);
        if (action == QLearningPolicy.Action.RETREAT) {
            tryWindCharge(server, session, bot);
        }
        if (!rangedActive) {
            tryCrystalBlast(server, session, bot, target);
        }
        applyAction(server, session, bot, target, action);
        if (action == QLearningPolicy.Action.STRAFE_LEFT || action == QLearningPolicy.Action.STRAFE_RIGHT) {
            lookAtCombatTarget(server, session, target);
        }
        if (!rangedActive) {
            tryCritMelee(server, session, bot, target, distance);
        }
        TrainingLog.record(session.tick, session.botName, session.targetName, state, action, reward, false);

        session.previousState = state;
        session.previousAction = action;
        session.lastBotHealth = bot.getHealth();
        session.lastTargetHealth = target.getHealth();
        if (session.decisions++ % 20 == 0) {
            session.policy.save();
        }
    }

    static void afterDeath(LivingEntity entity) {
        if (!(entity instanceof ServerPlayerEntity deadPlayer) || active == null) {
            return;
        }
        Session session = active;
        String deadName = deadPlayer.getName().getString();
        boolean botDown = session.botName.equalsIgnoreCase(deadName);
        boolean targetDown = session.targetName.equalsIgnoreCase(deadName);
        if (!botDown && !targetDown) {
            return;
        }

        ServerPlayerEntity bot = HeroBotAdapter.findPlayer(session.server, session.botName);
        ServerPlayerEntity target = HeroBotAdapter.findPlayer(session.server, session.targetName);
        double botDamage = session.lastBotHealth - (bot == null ? 0.0F : bot.getHealth());
        double targetDamage = session.lastTargetHealth - (target == null ? 0.0F : target.getHealth());
        double terminalReward = targetDown && !botDown ? 12.0 : botDown && !targetDown ? -12.0 : 0.0;
        double reward = targetDamage - 1.2 * botDamage + terminalReward;
        String state = session.previousState == null
                ? "d3_h1_air0_b0_o1_x0"
                : session.previousState;
        if (session.previousState != null && session.previousAction != null) {
            session.policy.update(session.previousState, session.previousAction, reward, null, true);
        }
        learnFromHuman(session, bot, target,
                botDamage + (botDown && !targetDown ? HUMAN_WIN_REWARD : 0.0), true);
        TrainingLog.record(session.tick, session.botName, session.targetName, state,
                session.previousAction == null ? QLearningPolicy.Action.HOLD : session.previousAction,
                reward, true);
        session.policy.save();
        finish(session, false, targetDown && !botDown ? "Sieg" : botDown && !targetDown ? "Niederlage" : "Unentschieden");
    }

    static void shutdown(MinecraftServer server) {
        if (pending != null && pending.server == server) {
            PendingStart request = pending;
            pending = null;
            HeroBotAdapter.disconnect(server, request.botName);
            TrainingLog.event("SPAWN_CANCELLED bot=" + request.botName + " reason=server_shutdown");
        }
        Session session = active;
        if (session != null && session.server == server) {
            finish(session, false, "Server wird beendet");
        }
    }

    static int stop(MinecraftServer server, ServerCommandSource source, boolean removeBot) {
        if (pending != null && pending.server == server) {
            pending.cancelled = true;
            HeroBotAdapter.disconnect(server, pending.botName);
            source.sendFeedback(() -> Text.literal("Spawn wird abgebrochen; ein verspätet beitretender Bot wird entfernt."), false);
            TrainingLog.event("SPAWN_CANCEL_REQUEST bot=" + pending.botName);
            return 1;
        }
        if (active == null || active.server != server) {
            source.sendError(Text.literal("Es läuft gerade kein PvP-Training."));
            return 0;
        }
        finish(active, removeBot, "Manuell gestoppt");
        return 1;
    }

    static int removeActiveBot(MinecraftServer server, ServerCommandSource source) {
        if (pending != null && pending.server == server) {
            String name = pending.botName;
            pending.cancelled = true;
            HeroBotAdapter.disconnect(server, name);
            source.sendFeedback(() -> Text.literal("Spawn abgebrochen; " + name + " wird bei verspätetem Beitritt entfernt."), false);
            TrainingLog.event("SPAWN_CANCEL_REQUEST bot=" + name + " remove=true");
            return 1;
        }
        if (active == null || active.server != server) {
            source.sendError(Text.literal("Kein aktiver Trainingsbot. Nutze /hba remove <BotName>."));
            return 0;
        }
        String name = active.botName;
        finish(active, false, "Bot entfernt");
        execute(server, "player " + name + " disconnect");
        source.sendFeedback(() -> Text.literal("Bot entfernt: " + name), false);
        return 1;
    }

    static int removeBot(MinecraftServer server, ServerCommandSource source, String botName) {
        if (pending != null && pending.server == server && pending.botName.equalsIgnoreCase(botName)) {
            pending.cancelled = true;
            HeroBotAdapter.disconnect(server, pending.botName);
            source.sendFeedback(() -> Text.literal("Spawn abgebrochen; " + pending.botName
                    + " wird bei verspätetem Beitritt entfernt."), false);
            TrainingLog.event("SPAWN_CANCEL_REQUEST bot=" + pending.botName + " remove=true");
            return 1;
        }
        if (active != null && active.server == server && active.botName.equalsIgnoreCase(botName)) {
            finish(active, false, "Bot entfernt");
        }
        ServerPlayerEntity candidate = HeroBotAdapter.findPlayer(server, botName);
        if (candidate == null || !HeroBotAdapter.isHeroBot(candidate)) {
            source.sendError(Text.literal("Kein HeroBot-Spieler mit diesem Namen: " + botName));
            return 0;
        }
        execute(server, "player " + botName + " disconnect");
        source.sendFeedback(() -> Text.literal("Bot entfernt: " + botName), false);
        return 1;
    }

    static int status(MinecraftServer server, ServerCommandSource source) {
        if (pending != null && pending.server == server) {
            PendingStart request = pending;
            source.sendFeedback(() -> Text.literal("HeroBot-Spawn wird verarbeitet: " + request.botName
                    + " gegen " + request.targetName + " · " + request.ticks + "/" + SPAWN_TIMEOUT_TICKS + " Ticks."), false);
            return 1;
        }
        if (active == null || active.server != server) {
            source.sendFeedback(() -> Text.literal("Kein aktives Training. Starte es mit /hba spawn [BotName] [Gegner]."), false);
            return 1;
        }
        Session session = active;
        String maceStatus = session.maceLoadout
                ? " · Elytra-Mace: " + session.macePhase.name().toLowerCase(Locale.ROOT) : "";
        source.sendFeedback(() -> Text.literal("Training aktiv: " + session.botName + " gegen " + session.targetName
                + " · Kit: " + session.kit.commandName() + " · Entscheidungen: " + session.decisions + " · Q-Updates: " + session.policy.updates()
                + " · Zustände: " + session.policy.stateCount() + maceStatus), false);
        source.sendFeedback(() -> Text.literal("Kampfdaten: " + TrainingLog.getPath()), false);
        return 1;
    }

    /**
     * W-Tap: innerhalb des Freigabe-Fensters (wTapUntilTick) keinen Sprint erzwingen,
     * sonst wird setSprint(false) nach dem Treffer sofort ueberschrieben und der naechste
     * Treffer ist wieder ein Sprint-Schlag ohne Knockback-Bonus.
     */
    private static void sprintIfAllowed(MinecraftServer server, Session session, String bot) {
        if (session.tick > session.wTapUntilTick) {
            execute(server, "player " + bot + " sprint");
        }
    }

    private static void applyAction(MinecraftServer server, Session session,
                                    ServerPlayerEntity botEntity, ServerPlayerEntity targetEntity,
                                    QLearningPolicy.Action action) {
        String bot = session.botName;
        // Beim Rueckzug NICHT aufs Ziel drehen: RETREAT dreht weiter unten auf den
        // Fluchtpunkt (delta 1). Ein sofortiger Blick aufs Ziel davor liese den Bot
        // zwischen beiden Zielen hin- und herschwenken, statt gerade wegzulaufen.
        if (action != QLearningPolicy.Action.RETREAT) {
            HeroBotAdapter.lookAtEntityNow(server, bot, session.targetName);
        }
        execute(server, "player " + bot + " move");
        switch (action) {
            case APPROACH -> {
                execute(server, "player " + bot + " move forward");
                sprintIfAllowed(server, session, bot);
            }
            case STRAFE_LEFT -> {
                execute(server, "player " + bot + " move forward");
                execute(server, "player " + bot + " move left");
                sprintIfAllowed(server, session, bot);
            }
            case STRAFE_RIGHT -> {
                execute(server, "player " + bot + " move forward");
                execute(server, "player " + bot + " move right");
                sprintIfAllowed(server, session, bot);
            }
            case RETREAT -> {
                Vec3d away = new Vec3d(botEntity.getX() - targetEntity.getX(), 0.0,
                        botEntity.getZ() - targetEntity.getZ());
                if (away.lengthSquared() < 1.0E-4) {
                    execute(server, "player " + bot + " move backward");
                    execute(server, "player " + bot + " unsprint");
                } else {
                    // Fluchtpunkt konstant entfernt: vorher stand hier bot + away, und away
                    // traegt den Betrag der AKTUELLEN Distanz. Bei d0 lag der Zielpunkt damit
                    // direkt neben dem Bot — er schaute dorthin und lief auf der Stelle, statt
                    // wegzulaufen.
                    Vec3d fleePoint = away.normalize().multiply(RETREAT_FLEE_DISTANCE)
                            .add(new Vec3d(botEntity.getX(), botEntity.getY() + 1.0, botEntity.getZ()));
                    HeroBotAdapter.lookAtPosition(server, bot, fleePoint, 1);
                    execute(server, "player " + bot + " move forward");
                    sprintIfAllowed(server, session, bot);
                }
            }
            case HOLD -> {
                execute(server, "player " + bot + " unsprint");
                execute(server, "player " + bot + (session.tick % 2 == 0 ? " move left" : " move right"));
            }
        }
    }

    private static void optimizeArmor(Session session, ServerPlayerEntity bot) {
        EquipmentSlot[] armorSlots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot armorSlot : armorSlots) {
            if (armorSlot == EquipmentSlot.CHEST && session.maceLoadout
                    && bot.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
                continue;
            }
            ItemStack equipped = bot.getEquippedStack(armorSlot);
            int bestScore = armorScore(equipped, armorSlot);
            int bestInventorySlot = -1;
            for (int slot = 0; slot < 36; slot++) {
                ItemStack candidate = bot.getInventory().getStack(slot);
                int score = armorScore(candidate, armorSlot);
                if (score > bestScore) {
                    bestScore = score;
                    bestInventorySlot = slot;
                }
            }
            if (bestInventorySlot >= 0) {
                ItemStack candidate = bot.getInventory().getStack(bestInventorySlot).copy();
                bot.getInventory().setStack(bestInventorySlot, equipped.copy());
                bot.equipStack(armorSlot, candidate);
                TrainingLog.event("ARMOR_EQUIP bot=" + session.botName + " slot=" + armorSlot.name().toLowerCase(Locale.ROOT)
                        + " item=" + itemPath(candidate));
            }
        }
    }

    private static int armorScore(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) return -1000;
        String path = itemPath(stack);
        boolean matchingSlot = switch (slot) {
            case HEAD -> path.endsWith("_helmet") || path.equals("turtle_helmet");
            case CHEST -> path.endsWith("_chestplate");
            case LEGS -> path.endsWith("_leggings");
            case FEET -> path.endsWith("_boots");
            default -> false;
        };
        if (!matchingSlot) return -1000;

        int tier = materialTier(path);
        if (path.equals("turtle_helmet")) tier = Math.max(tier, 2);
        int score = tier * 100;
        int maxDamage = stack.getMaxDamage();
        if (maxDamage > 0) {
            int remaining = maxDamage - stack.getDamage();
            if (remaining <= 5) score -= 180;
            else if (remaining <= 20) score -= 60;
        }
        return score;
    }

    private static int chooseWeaponSlot(ServerPlayerEntity bot, ServerPlayerEntity target) {
        int selected = bot.getInventory().getSelectedSlot();
        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = bot.getInventory().getStack(slot);
            int score = weaponScore(stack, bot, target);
            if (slot == selected && score > Integer.MIN_VALUE) score += 1;
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestScore <= Integer.MIN_VALUE / 2) {
            return -1;
        }
        if (bestSlot >= 9) {
            int hotbarSlot = Math.max(0, Math.min(8, selected));
            ItemStack weapon = bot.getInventory().getStack(bestSlot).copy();
            ItemStack displaced = bot.getInventory().getStack(hotbarSlot).copy();
            bot.getInventory().setStack(bestSlot, displaced);
            bot.getInventory().setStack(hotbarSlot, weapon);
            TrainingLog.event("WEAPON_INVENTORY_SWAP bot=" + bot.getName().getString()
                    + " slot=" + bestSlot + " -> hotbar." + hotbarSlot + " item=" + itemPath(weapon));
            return hotbarSlot;
        }
        return bestSlot;
    }

    private static int weaponScore(ItemStack stack, ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (stack.isEmpty()) return Integer.MIN_VALUE;
        String path = itemPath(stack);
        int typeBonus;
        if (path.endsWith("_sword")) typeBonus = 20;
        else if (path.endsWith("_axe")) typeBonus = 24;
        else if (path.endsWith("_spear")) typeBonus = 15;
        else if (path.equals("mace")) typeBonus = 10;
        else if (path.equals("trident")) typeBonus = 8;
        else return Integer.MIN_VALUE;

        int score = materialTier(path) * 100 + typeBonus;
        if (path.endsWith("_axe") && target != null
                && target.isUsingItem() && target.getActiveItem().isOf(Items.SHIELD)) {
            score += 100;
        }
        // Crystal-Logik: Das Schwert ist Hauptwaffe, die Axt nur Schildbrecher.
        // Nur wenn der Gegner tatsaechlich blockt, wird die Axt vorgezogen.
        boolean targetBlocking = target != null
                && target.isUsingItem() && target.getActiveItem().isOf(Items.SHIELD);
        if (path.endsWith("_axe") && !targetBlocking) score -= 30;
        if (path.endsWith("_sword") && targetBlocking) score -= 40;
        if (path.equals("mace") && target != null
                && (bot.isGliding() || bot.getY() > target.getY() + 2.0)) {
            score += 400;
        }
        if (path.endsWith("_spear") && target != null && bot.distanceTo(target) > 3.0) {
            score += 45;
        }
        int maxDamage = stack.getMaxDamage();
        if (maxDamage > 0 && maxDamage - stack.getDamage() <= 3) score -= 1000;
        return score;
    }

    private static int materialTier(String itemPath) {
        if (itemPath.startsWith("netherite_")) return 6;
        if (itemPath.startsWith("diamond_")) return 5;
        if (itemPath.startsWith("iron_")) return 4;
        if (itemPath.startsWith("stone_")) return 3;
        if (itemPath.startsWith("golden_")) return 2;
        if (itemPath.startsWith("wooden_") || itemPath.startsWith("bamboo_")) return 1;
        return 2;
    }

    private static String itemPath(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).getPath();
    }

    private static boolean hasMaceFlightLoadout(ServerPlayerEntity bot) {
        if (!bot.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) return false;
        boolean mace = false;
        boolean rocket = false;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = bot.getInventory().getStack(slot);
            mace |= stack.isOf(Items.MACE);
            rocket |= stack.isOf(Items.FIREWORK_ROCKET);
        }
        return mace && rocket;
    }

    private static boolean moveShieldToOffhand(ServerPlayerEntity bot) {
        if (bot.getOffHandStack().isOf(Items.SHIELD)) return true;
        // Totem-Priorität: ein schon im Offhand liegendes Totem bleibt stehen.
        if (bot.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return false;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack candidate = bot.getInventory().getStack(slot);
            if (candidate.isOf(Items.SHIELD)) {
                ItemStack replaced = bot.getOffHandStack().copy();
                bot.equipStack(EquipmentSlot.OFFHAND, candidate.copy());
                bot.getInventory().setStack(slot, replaced);
                return true;
            }
        }
        return false;
    }

    private static boolean moveTotemToOffhand(ServerPlayerEntity bot) {
        if (bot.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return true;
        if (!bot.getOffHandStack().isEmpty()) return false;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack candidate = bot.getInventory().getStack(slot);
            if (candidate.isOf(Items.TOTEM_OF_UNDYING)) {
                ItemStack replaced = bot.getOffHandStack().copy();
                bot.equipStack(EquipmentSlot.OFFHAND, candidate.copy());
                bot.getInventory().setStack(slot, replaced);
                return true;
            }
        }
        return false;
    }

    private static void updateShield(Session session, ServerPlayerEntity bot, ServerPlayerEntity target) {
        boolean hasShield = moveShieldToOffhand(bot);
        if (!hasShield) {
            moveTotemToOffhand(bot);
        }
        Vec3d toBot = new Vec3d(bot.getX() - target.getX(), 0.0, bot.getZ() - target.getZ()).normalize();
        Vec3d targetFacing = target.getRotationVec(1.0F);
        targetFacing = new Vec3d(targetFacing.x, 0.0, targetFacing.z).normalize();
        boolean targetAimingAtBot = targetFacing.dotProduct(toBot) >= 0.45;
        boolean threatClose = bot.distanceTo(target) <= 5.0;
        boolean targetBlocking = target.isUsingItem() && target.getActiveItem().isOf(Items.SHIELD);
        boolean underThreat = session.tick - session.lastBotDamageTick <= 16;
        boolean triggerAvailable = session.tick - session.lastShieldRaiseTick >= SHIELD_RETRIGGER_COOLDOWN_TICKS;
        boolean shouldShield = hasShield && !session.healing && threatClose && targetAimingAtBot
                && underThreat && triggerAvailable;
        if (session.shielding && session.tick - session.lastShieldRaiseTick >= SHIELD_MAX_HOLD_TICKS
                && !underThreat) {
            shouldShield = false;
        }
        if (shouldShield != session.shielding) {
            if (shouldShield) {
                session.lastShieldRaiseTick = session.tick;
            }
            HeroBotAdapter.setUsing(session.server, session.botName, shouldShield);
            session.shielding = shouldShield;
            TrainingLog.event("SHIELD_" + (shouldShield ? "RAISE" : "LOWER") + " bot=" + session.botName
                    + " target=" + session.targetName + " target_blocking=" + targetBlocking
                    + " recent_bot_damage=" + (session.tick - session.lastBotDamageTick <= 16));
        }
    }

    private static void lookAtCombatTarget(MinecraftServer server, Session session, ServerPlayerEntity target) {
        HeroBotAdapter.lookAtPosition(server, session.botName,
                new Vec3d(target.getX(), target.getY() + 0.9, target.getZ()), 1);
    }

    /**
     * Startet den Elytra-Mace-Opener erneut. Bislang lief er nur einmal nach dem Spawn
     * (danach blieb macePhase dauerhaft DONE). Bedingung: Cooldown abgelaufen, kein
     * Rueckzug/Heilen/Fernkampf, Bot steht mit Abstand am Boden, Loadout noch vollstaendig.
     */
    private static void maybeRestartMaceOpener(Session session, ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (!session.maceLoadout || session.macePhase != MacePhase.DONE) {
            return;
        }
        if (session.tick - session.lastMaceOpenerTick < MACE_OPENER_COOLDOWN_TICKS) {
            return;
        }
        if (session.retreating || session.healing || session.rangedActive) {
            return;
        }
        if (!bot.isOnGround()) {
            return;
        }
        double distance = bot.distanceTo(target);
        if (distance < 6.0 || distance > 24.0 || !hasMaceFlightLoadout(bot)) {
            return;
        }
        beginMaceOpener(session, bot, target);
    }

    private static void beginMaceOpener(Session session, ServerPlayerEntity bot, ServerPlayerEntity target) {
        MinecraftServer server = session.server;
        // Auch bei Abbruch den Cooldown starten, sonst wird der Opener jeden Tick erneut versucht.
        session.lastMaceOpenerTick = session.tick;
        double launchY = target.getBlockY() + MACE_LAUNCH_HEIGHT;
        if (launchY + 2 >= target.getEntityWorld().getTopYInclusive()
                || !hasClearMaceColumn(bot, target, (int) launchY)) {
            session.macePhase = MacePhase.DONE;
            execute(server, "player " + session.botName + " autojump true");
            // Siehe Start: kein attack continuous, sonst trifft tryCritMelee immer mit leerem
            // Attack-Strength-Meter an.
            TrainingLog.event("MACE_SKIP bot=" + session.botName + " reason=no_clear_vertical_space");
            return;
        }

        session.maceInitialBotHealth = bot.getHealth();
        session.maceInitialTargetHealth = target.getHealth();
        session.maceState = session.policy.observe(bot.distanceTo(target), bot.getHealth(),
                target.getHealth(), target.isOnGround(), isBlocking(target), hasAxe(bot));
        HeroBotAdapter.setAttacking(server, session.botName, false);
        HeroBotAdapter.setAutoJump(server, session.botName, false);
        HeroBotAdapter.teleport(server, session.botName,
                new Vec3d(bot.getX(), launchY, bot.getZ()), bot.getYaw(), -15.0F);
        aimClimb(server, session, bot, target);
        HeroBotAdapter.move(server, session.botName, "forward");
        HeroBotAdapter.setSprint(server, session.botName, true);
        session.macePhase = MacePhase.WAIT_GLIDE;
        session.macePhaseTicks = 0;
        TrainingLog.event("MACE_SETUP bot=" + session.botName + " target=" + session.targetName
                + " launch_y=" + String.format(Locale.ROOT, "%.1f", launchY));
    }

    private static boolean hasClearMaceColumn(ServerPlayerEntity bot, ServerPlayerEntity target, int launchY) {
        int bottomY = Math.min(bot.getBlockY(), target.getBlockY()) + 1;
        int botX = bot.getBlockX();
        int botZ = bot.getBlockZ();
        int targetX = target.getBlockX();
        int targetZ = target.getBlockZ();
        for (int y = bottomY; y <= launchY + 1; y++) {
            if (!target.getEntityWorld().getBlockState(new BlockPos(botX, y, botZ)).isAir()
                    || !target.getEntityWorld().getBlockState(new BlockPos(targetX, y, targetZ)).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static void tickMaceOpener(Session session, ServerPlayerEntity bot, ServerPlayerEntity target) {
        MinecraftServer server = session.server;
        session.macePhaseTicks++;
        switch (session.macePhase) {
            case WAIT_GLIDE -> {
                if (bot.isGliding()) {
                    HeroBotAdapter.selectHotbar(server, session.botName, 1);
                    aimClimb(server, session, bot, target);
                    HeroBotAdapter.useOnce(server, session.botName);
                    session.maceBoosts++;
                    session.maceBoostTick = session.tick;
                    session.macePhase = MacePhase.CLIMB;
                    session.macePhaseTicks = 0;
                    TrainingLog.event("MACE_GLIDE bot=" + session.botName + " firework_boost=" + session.maceBoosts);
                    return;
                }
                if (session.macePhaseTicks == 3) {
                    aimClimb(server, session, bot, target);
                    HeroBotAdapter.jump(server, session.botName);
                }
                if (session.macePhaseTicks >= 24 || bot.getY() <= target.getY() + 3) {
                    finishMaceOpener(session, bot, target, "elytra_activation_failed");
                }
            }
            case CLIMB -> {
                if (session.tick % 5 == 0) {
                    aimClimb(server, session, bot, target);
                    HeroBotAdapter.move(server, session.botName, "forward");
                }
                if (session.maceBoosts < 2 && session.tick - session.maceBoostTick >= 18
                        && bot.getY() < target.getY() + MACE_CLIMB_HEIGHT
                        && bot.getInventory().getStack(1).isOf(Items.FIREWORK_ROCKET)) {
                    HeroBotAdapter.selectHotbar(server, session.botName, 1);
                    HeroBotAdapter.useOnce(server, session.botName);
                    session.maceBoosts++;
                    session.maceBoostTick = session.tick;
                    TrainingLog.event("MACE_BOOST bot=" + session.botName + " count=" + session.maceBoosts);
                }
                if (bot.getY() >= target.getY() + MACE_CLIMB_HEIGHT
                        || session.macePhaseTicks >= MACE_MAX_CLIMB_TICKS
                        || (session.macePhaseTicks >= 12 && !bot.isGliding())) {
                    startMaceDive(session, bot, target);
                }
            }
            case DIVE -> {
                if (session.tick % 4 == 0) {
                    HeroBotAdapter.lookAtEntityNow(server, session.botName, session.targetName);
                    HeroBotAdapter.move(server, session.botName, "forward");
                }
                if (target.getHealth() < session.maceInitialTargetHealth) {
                    finishMaceOpener(session, bot, target, "mace_hit");
                } else if (bot.isOnGround() || bot.getY() <= target.getY() + 1.0
                        || session.macePhaseTicks >= MACE_DIVE_TIMEOUT_TICKS) {
                    finishMaceOpener(session, bot, target, "dive_missed");
                }
            }
            case DONE -> { }
        }
        session.lastBotHealth = bot.getHealth();
        session.lastTargetHealth = target.getHealth();
    }

    private static void aimClimb(MinecraftServer server, Session session,
                                 ServerPlayerEntity bot, ServerPlayerEntity target) {
        double dx = target.getX() - bot.getX();
        double dz = target.getZ() - bot.getZ();
        double horizontalDistance = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
        Vec3d aim = new Vec3d(bot.getX() + dx / horizontalDistance * 20.0,
                bot.getY() + 9.0, bot.getZ() + dz / horizontalDistance * 20.0);
        HeroBotAdapter.lookAtPosition(server, session.botName, aim, 3);
    }

    private static void startMaceDive(Session session, ServerPlayerEntity bot, ServerPlayerEntity target) {
        session.macePhase = MacePhase.DIVE;
        session.macePhaseTicks = 0;
        HeroBotAdapter.selectHotbar(session.server, session.botName, 0);
        HeroBotAdapter.lookAtEntityNow(session.server, session.botName, session.targetName);
        HeroBotAdapter.move(session.server, session.botName, "forward");
        HeroBotAdapter.setSprint(session.server, session.botName, true);
        HeroBotAdapter.setAttacking(session.server, session.botName, true);
        TrainingLog.event("MACE_DIVE bot=" + session.botName + " target=" + session.targetName
                + " height=" + String.format(Locale.ROOT, "%.1f", bot.getY() - target.getY()));
    }

    private static void finishMaceOpener(Session session, ServerPlayerEntity bot,
                                         ServerPlayerEntity target, String reason) {
        double botDamage = Math.max(0.0, session.maceInitialBotHealth - bot.getHealth());
        double targetDamage = Math.max(0.0, session.maceInitialTargetHealth - target.getHealth());
        double reward = targetDamage - 1.2 * botDamage;
        String nextState = session.policy.observe(bot.distanceTo(target), bot.getHealth(),
                target.getHealth(), target.isOnGround(), isBlocking(target), hasAxe(bot));
        session.policy.update(session.maceState, QLearningPolicy.Action.APPROACH, reward, nextState, false);
        TrainingLog.record(session.tick, session.botName, session.targetName, session.maceState,
                QLearningPolicy.Action.APPROACH, reward, false);
        session.macePhase = MacePhase.DONE;
        session.decisions++;
        session.previousState = nextState;
        session.previousAction = QLearningPolicy.Action.APPROACH;
        session.lastBotHealth = bot.getHealth();
        session.lastTargetHealth = target.getHealth();
        HeroBotAdapter.setAutoJump(session.server, session.botName, true);
        HeroBotAdapter.setAttacking(session.server, session.botName, false);
        TrainingLog.event("MACE_END bot=" + session.botName + " target=" + session.targetName
                + " result=" + reason + " damage=" + String.format(Locale.ROOT, "%.1f", targetDamage)
                + " self_damage=" + String.format(Locale.ROOT, "%.1f", botDamage));
    }

    /** Fernkampf mit Bogen/Armbrust. Gibt true zurück, solange der Fernkampf läuft. */
    private static boolean tryRangedCombat(MinecraftServer server, Session session,
                                           ServerPlayerEntity bot, ServerPlayerEntity target,
                                           double distance) {
        if (!session.rangedActive) {
            if (distance < RANGED_MIN_DISTANCE || distance > RANGED_MAX_DISTANCE) {
                return false;
            }
            // Armbrust zuerst: in den Kits bereits geladen, feuert sofort.
            int slot = findHotbarSlot(bot, Items.CROSSBOW);
            if (slot < 0) {
                int bowSlot = findHotbarSlot(bot, Items.BOW);
                if (bowSlot >= 0 && hasArrows(bot)) {
                    slot = bowSlot;
                }
            }
            if (slot < 0) {
                return false;
            }
            if (bot.getInventory().getSelectedSlot() != slot) {
                HeroBotAdapter.selectHotbar(server, session.botName, slot);
            }
            HeroBotAdapter.setUsing(server, session.botName, true);
            session.rangedActive = true;
            TrainingLog.event("RANGE_DRAW bot=" + session.botName
                    + " item=" + itemPath(bot.getInventory().getStack(slot))
                    + " slot=" + slot
                    + " distance=" + String.format(Locale.ROOT, "%.2f", distance));
            return true;
        }

        ItemStack held = bot.getInventory().getStack(bot.getInventory().getSelectedSlot());
        boolean holdsRanged = held.isOf(Items.BOW) || held.isOf(Items.CROSSBOW);
        if (distance <= RANGED_STOP_DISTANCE || !holdsRanged) {
            // Wiki: use ohne Parameter stoppt das Halten (Bogen wird losgelassen, feuert ab).
            HeroBotAdapter.setUsing(server, session.botName, false);
            session.rangedActive = false;
            TrainingLog.event("RANGE_STOP bot=" + session.botName
                    + " distance=" + String.format(Locale.ROOT, "%.2f", distance));
            return false;
        }
        return true;
    }

    /** Beim Rückzug eine Wind Charge werfen (Rückstoß für Abstand). */
    private static void tryWindCharge(MinecraftServer server, Session session, ServerPlayerEntity bot) {
        if (session.tick - session.lastWindChargeTick < WIND_CHARGE_COOLDOWN_TICKS) {
            return;
        }
        int slot = findHotbarSlot(bot, Items.WIND_CHARGE);
        if (slot < 0) {
            return;
        }
        int previousSlot = bot.getInventory().getSelectedSlot();
        HeroBotAdapter.selectHotbar(server, session.botName, slot);
        // Nutzen im Slot (Mainhand + Offhand im selben Tick); "once" ist die einzige
        // zulaessige Variante neben continuous/interval.
        HeroBotAdapter.useOnce(server, session.botName);
        // Slot-Rueckwechsel erst im Folgetick (siehe trySplashBuff).
        if (previousSlot != slot) {
            session.pendingHotbarRestore = previousSlot;
        }
        session.lastWindChargeTick = session.tick;
        TrainingLog.event("WIND_CHARGE bot=" + session.botName
                + " slot=" + slot
                + " offhand=" + itemPath(bot.getEquippedStack(net.minecraft.entity.EquipmentSlot.OFFHAND)));
    }

    /** Ersten Hotbar-Slot mit dem gesuchten Item suchen, sonst -1. */
    private static int findHotbarSlot(ServerPlayerEntity bot, net.minecraft.item.Item item) {
        for (int slot = 0; slot < 9; slot++) {
            if (bot.getInventory().getStack(slot).isOf(item)) {
                return slot;
            }
        }
        return -1;
    }

    /** Pfeile im Inventar (Hotbar oder Rucksack), nur der Bogen braucht sie. */
    private static boolean hasArrows(ServerPlayerEntity bot) {
        for (int slot = 0; slot < 36; slot++) {
            if (bot.getInventory().getStack(slot).isOf(Items.ARROW)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wirft eine Splash-Potion (Stärke/Schnelligkeit) unter die eigenen Füße, wenn der Bot
     * den Effekt noch nicht hat. Der Potions-Typ wird über die Komponente gelesen.
     */
    private static void trySplashBuff(MinecraftServer server, Session session, ServerPlayerEntity bot) {
        if (session.pendingSplashRestore) {
            return;
        }
        if (session.tick - session.lastSplashBuffTick < SPLASH_BUFF_COOLDOWN_TICKS) {
            return;
        }
        boolean hasStrength = bot.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.STRENGTH);
        boolean hasSwiftness = bot.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.SPEED);
        if (hasStrength && hasSwiftness) {
            return; // nichts zu buffen
        }
        String want = !hasStrength ? "strength" : "swiftness";
        int slot = findSplashPotion(bot, want);
        if (slot < 0) {
            return;
        }
        int held = bot.getInventory().getSelectedSlot();
        if (held == slot) {
            return; // Potion liegt schon in der Hand — nichts zu verschieben
        }
        ItemStack savedHeld = bot.getInventory().getStack(held).copy();
        ItemStack potion = bot.getInventory().getStack(slot).copy();
        bot.getInventory().setStack(slot, ItemStack.EMPTY);
        bot.getInventory().setStack(held, potion);
        // Nach unten zielen, damit die Splash-Potion auf den eigenen Füßen platzt.
        HeroBotAdapter.lookAtPosition(server, session.botName,
                new Vec3d(bot.getX(), bot.getY() - 1.0, bot.getZ()), 1);
        HeroBotAdapter.useOnce(server, session.botName);
        // NICHT im selben Tick zuruecktauschen: HeroBot wertet "use once" erst im Folgetick
        // aus, der Ruecktausch liess die Potion damit nie fliegen (belegt im Log: der Bot
        // hatte den Stärke-Effekt dauerhaft nicht).
        session.pendingSplashRestore = true;
        session.splashPotionSlot = slot;
        session.splashHeldSlot = held;
        session.splashSavedHeld = savedHeld;
        session.lastSplashBuffTick = session.tick;
        TrainingLog.event("SPLASH_BUFF bot=" + session.botName + " potion=" + want
                + " slot=" + slot + " held=" + held);
    }

    /**
     * Wirft eine Splash-Potion of Damage auf den Gegner, wenn der Bot die Waffe nicht gerade
     * zum Fernkampf braucht. Zielt auf die Füße des Ziels, damit die Splash-Explosion trifft.
     * Slot-Ruecktausch wie in trySplashBuff erst im Folgetick.
     */
    private static void trySplashDamage(MinecraftServer server, Session session,
                                        ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (session.pendingSplashRestore) {
            return;
        }
        if (session.tick - session.lastSplashDamageTick < SPLASH_DAMAGE_COOLDOWN_TICKS) {
            return;
        }
        double distance = bot.distanceTo(target);
        if (distance > SPLASH_DAMAGE_MAX_DISTANCE || !bot.canSee(target)) {
            return;
        }
        int slot = findSplashPotion(bot, "damage");
        if (slot < 0) {
            return;
        }
        int held = bot.getInventory().getSelectedSlot();
        if (held == slot) {
            return; // Potion liegt schon in der Hand
        }
        ItemStack savedHeld = bot.getInventory().getStack(held).copy();
        ItemStack potion = bot.getInventory().getStack(slot).copy();
        bot.getInventory().setStack(slot, ItemStack.EMPTY);
        bot.getInventory().setStack(held, potion);
        // Auf die Füße des Ziels zielen: die Splash-Explosion trifft alles im Radius darum.
        HeroBotAdapter.lookAtPositionNow(server, session.botName,
                new Vec3d(target.getX(), target.getY() + 0.1, target.getZ()));
        HeroBotAdapter.useOnce(server, session.botName);
        // Ruecktausch erst im Folgetick, sonst fliegt die Potion nie (siehe trySplashBuff).
        session.pendingSplashRestore = true;
        session.splashPotionSlot = slot;
        session.splashHeldSlot = held;
        session.splashSavedHeld = savedHeld;
        session.lastSplashDamageTick = session.tick;
        TrainingLog.event("SPLASH_DAMAGE bot=" + session.botName + " target=" + session.targetName
                + " slot=" + slot + " distance=" + String.format(Locale.ROOT, "%.2f", distance));
    }

    /** Findet eine Splash-Potion des gewünschten Typs im gesamten Inventar, sonst -1. */
    private static int findSplashPotion(ServerPlayerEntity bot, String want) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = bot.getInventory().getStack(slot);
            if (!stack.isOf(Items.SPLASH_POTION)) {
                continue;
            }
            net.minecraft.component.type.PotionContentsComponent contents =
                    stack.get(net.minecraft.component.DataComponentTypes.POTION_CONTENTS);
            if (contents == null) {
                continue;
            }
            for (net.minecraft.entity.effect.StatusEffectInstance instance : contents.getEffects()) {
                net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.effect.StatusEffect> type =
                        instance.getEffectType();
                if (("strength".equals(want) && type == net.minecraft.entity.effect.StatusEffects.STRENGTH)
                        || ("swiftness".equals(want) && type == net.minecraft.entity.effect.StatusEffects.SPEED)
                        || ("damage".equals(want)
                            && type == net.minecraft.entity.effect.StatusEffects.INSTANT_DAMAGE)) {
                    return slot;
                }
            }
        }
        return -1;
    }

    private static void tryPearl(MinecraftServer server, Session session,
                                 ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (bot.distanceTo(target) < PEARL_DISTANCE
                || session.tick - session.lastPearlTick < PEARL_COOLDOWN_TICKS) {
            return;
        }

        int pearlSlot = -1;
        for (int slot = 0; slot < 9; slot++) {
            if (bot.getInventory().getStack(slot).isOf(Items.ENDER_PEARL)) {
                pearlSlot = slot;
                break;
            }
        }
        if (pearlSlot < 0) {
            return;
        }

        int previousSlot = bot.getInventory().getSelectedSlot();
        execute(server, "player " + session.botName + " look upon " + session.targetName);
        execute(server, "player " + session.botName + " hotbar " + (pearlSlot + 1));
        execute(server, "player " + session.botName + " use once");
        // Wiki: itemCd reset hebt die Abklingzeit sofort auf — der Bot kann sofort wieder werfen.
        HeroBotAdapter.resetItemCooldown(server, session.botName, "ender_pearl");
        // Slot-Rueckwechsel erst im Folgetick: HeroBot wertet "use once" erst dort aus.
        if (previousSlot != pearlSlot) {
            session.pendingHotbarRestore = previousSlot;
        }
        session.lastPearlTick = session.tick;
        TrainingLog.event("PEARL bot=" + session.botName + " target=" + session.targetName
                + " distance=" + String.format(Locale.ROOT, "%.2f", bot.distanceTo(target)));
    }

    /**
     * Startet einen Crystal-Blast. Die Arbeit selbst macht {@link #tickCrystalBlast}: HeroBot
     * wertet Befehle pro Tick aus, deshalb darf hier nichts gestapelt werden.
     */
    private static void tryCrystalBlast(MinecraftServer server, Session session,
                                        ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (session.crystalPhase != CrystalPhase.IDLE) {
            return; // ein Blast laeuft noch
        }
        double distance = bot.distanceTo(target);
        if (distance < CRYSTAL_MIN_DISTANCE || distance > CRYSTAL_MAX_DISTANCE
                || bot.getHealth() < CRYSTAL_MIN_HEALTH
                || session.tick - session.lastCrystalTick < CRYSTAL_COOLDOWN_TICKS) {
            return;
        }

        int crystalSlot = findHotbarSlot(bot, Items.END_CRYSTAL);
        if (crystalSlot < 0) {
            return;
        }

        // Zielposition: 1,2 Blöcke vor dem Bot auf dessen Bodenhöhe.
        Vec3d toTarget = new Vec3d(target.getX() - bot.getX(), 0.0, target.getZ() - bot.getZ()).normalize();
        Vec3d ground = new Vec3d(bot.getX() + toTarget.x * 1.2,
                Math.floor(bot.getY()) - 1.0, bot.getZ() + toTarget.z * 1.2);

        session.crystalBase = ground;
        session.crystalReturnSlot = bot.getInventory().getSelectedSlot();
        session.lastCrystalTick = session.tick;
        session.crystalNextTick = session.tick + 1;

        // Obsidian-Basis vorhanden? End-Crystals brauchen Obsidian oder Bedrock.
        if (isCrystalBaseAt(bot, ground)) {
            session.crystalPhase = CrystalPhase.OBIS_PLACED;
        } else {
            int obiSlot = findHotbarSlot(bot, Items.OBSIDIAN);
            if (obiSlot < 0) {
                session.crystalPhase = CrystalPhase.IDLE;
                return; // keine Basis -> kein sinnvoller Blast
            }
            // Phase 1: Blicke, Slot und Wurf — das Auswerten passiert im Folgetick.
            HeroBotAdapter.selectHotbar(server, session.botName, obiSlot);
            HeroBotAdapter.lookAtPosition(server, session.botName, ground, 1);
            HeroBotAdapter.useOnce(server, session.botName);
            session.crystalPhase = CrystalPhase.OBIS_PLACED;
            session.crystalNextTick = session.tick + 2; // Platzierung muss durch sein
        }
    }

    private static boolean isCrystalBaseAt(ServerPlayerEntity bot, Vec3d pos) {
        var state = bot.getEntityWorld().getBlockState(new BlockPos(
                (int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z)));
        return state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.BEDROCK);
    }

    /**
     * Laeuft jeden Tick ( unabhaengig vom Decision-Intervall ), damit die Phasen
     * tatsaechlich auseinanderfallen.
     */
    private static void tickCrystalBlast(MinecraftServer server, Session session,
                                         ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (session.crystalPhase == CrystalPhase.IDLE || session.crystalPhase == CrystalPhase.DONE) {
            return;
        }
        if (session.tick < session.crystalNextTick) {
            return;
        }
        Vec3d ground = session.crystalBase;
        Vec3d crystalBase = ground.add(0.0, 1.0, 0.0); // oben auf dem Bodenblock

        switch (session.crystalPhase) {
            case OBIS_PLACED -> {
                if (!isCrystalBaseAt(bot, ground)) {
                    // Wurf hat nicht gegriffen (Winkel, Abstand oder Kollision) — abbrechen.
                    TrainingLog.event("CRYSTAL_OBI_FAIL bot=" + session.botName);
                    restoreCrystalSlot(server, session, bot);
                    session.crystalPhase = CrystalPhase.IDLE;
                    return;
                }
                int crystalSlot = findHotbarSlot(bot, Items.END_CRYSTAL);
                if (crystalSlot < 0) {
                    restoreCrystalSlot(server, session, bot);
                    session.crystalPhase = CrystalPhase.IDLE;
                    return;
                }
                HeroBotAdapter.selectHotbar(server, session.botName, crystalSlot);
                HeroBotAdapter.lookAtPosition(server, session.botName, crystalBase, 1);
                HeroBotAdapter.useOnce(server, session.botName);
                session.crystalPhase = CrystalPhase.CRYSTAL_PLACED;
                session.crystalNextTick = session.tick + 2;
                TrainingLog.event("CRYSTAL_OBI_PLACE bot=" + session.botName + " confirmed=true");
            }
            case CRYSTAL_PLACED -> {
                // Zuenden: Blick auf den Kristall, dann ein Schlag.
                HeroBotAdapter.lookAtPosition(server, session.botName, crystalBase.add(0.0, 0.5, 0.0), 1);
                HeroBotAdapter.attackOnce(server, session.botName);
                restoreCrystalSlot(server, session, bot);
                session.crystalPhase = CrystalPhase.IDLE;
                TrainingLog.event("CRYSTAL_DETONATE bot=" + session.botName + " target=" + session.targetName
                        + " distance=" + String.format(Locale.ROOT, "%.2f", bot.distanceTo(target)));
            }
            default -> session.crystalPhase = CrystalPhase.IDLE;
        }
    }

    /** Slot zurueck auf die Kampf-Waffe; der Kopf bleibt nicht auf dem Boden stehen. */
    private static void restoreCrystalSlot(MinecraftServer server, Session session, ServerPlayerEntity bot) {
        int current = bot.getInventory().getSelectedSlot();
        if (session.crystalReturnSlot >= 0 && current != session.crystalReturnSlot) {
            HeroBotAdapter.selectHotbar(server, session.botName,
                    findCombatSlot(bot, session.crystalReturnSlot));
        }
    }

    /**
     * Schreibt die im VORHERIGEN Tick gesetzten Stacks zurueck und wechselt ggf. zurueck auf
     * die Kampf-Waffe. Erst ein Tick nach der Aktion ist HeroBot sie losgeworden.
     */
    private static void applyPendingRestores(MinecraftServer server, Session session,
                                             ServerPlayerEntity bot) {
        if (session.pendingSplashRestore) {
            session.pendingSplashRestore = false;
            // Was nach dem Wurf noch in der Hand liegt (leer, wenn die Potion geflogen ist),
            // geht zurueck in den Ursprungsslot; die alte Hand kommt wieder rein.
            if (session.splashPotionSlot >= 0 && session.splashHeldSlot >= 0) {
                ItemStack remaining = bot.getInventory().getStack(session.splashHeldSlot).copy();
                bot.getInventory().setStack(session.splashPotionSlot, remaining);
                bot.getInventory().setStack(session.splashHeldSlot, session.splashSavedHeld);
            }
            session.splashPotionSlot = -1;
            session.splashHeldSlot = -1;
            session.splashSavedHeld = null;
        }
        if (session.pendingRestoreSlotA >= 0) {
            bot.getInventory().setStack(session.pendingRestoreSlotA, session.pendingRestoreStackA);
            session.pendingRestoreSlotA = -1;
            session.pendingRestoreStackA = null;
        }
        if (session.pendingRestoreSlotB >= 0) {
            bot.getInventory().setStack(session.pendingRestoreSlotB, session.pendingRestoreStackB);
            session.pendingRestoreSlotB = -1;
            session.pendingRestoreStackB = null;
        }
        if (session.pendingHotbarRestore >= 0) {
            int wanted = findCombatSlot(bot, session.pendingHotbarRestore);
            session.pendingHotbarRestore = -1;
            if (bot.getInventory().getSelectedSlot() != wanted) {
                HeroBotAdapter.selectHotbar(server, session.botName, wanted);
            }
        }
    }

    /** Angriffs-Cooldown der Waffe in Ticks (1.9+-Combat). Ziel: >=84,8 % -> Crit/SB/Sweep möglich. */
    private static int weaponCooldownTicks(ItemStack stack) {
        if (stack.isEmpty()) return 20;
        String path = itemPath(stack);
        if (path.endsWith("_sword")) return 11;
        if (path.endsWith("_axe")) return 17;
        if (path.equals("mace")) return 28;
        if (path.endsWith("_spear")) return 12;
        if (path.equals("trident")) return 20;
        return 20;
    }

    private static void tryCritMelee(MinecraftServer server, Session session,
                                     ServerPlayerEntity bot, ServerPlayerEntity target, double distance) {
        // Laufender Crit-Nachschlag wird von tickCritAttack() abgearbeitet, nicht hier.
        if (session.pendingCritAttackTick != NO_PENDING_CRIT) {
            return;
        }
        if (distance > MANUAL_ATTACK_DISTANCE || !bot.canSee(target)
                || session.tick - session.lastManualAttackTick < MANUAL_ATTACK_INTERVAL_TICKS) {
            return;
        }
        // Waffen-Cooldown-Gate: erster Schlag sofort, danach erst, wenn die Waffe wieder 84,8 % hat.
        int needed = weaponCooldownTicks(bot.getMainHandStack());
        if (session.lastManualAttackTick > 0
                && session.tick - session.lastManualAttackTick < needed) {
            return;
        }
        HeroBotAdapter.lookAtEntityNow(server, session.botName, session.targetName);
        boolean nearCritSpam = distance <= 2.5;
        boolean goCrit = nearCritSpam || session.meleeSwingCount % 3 == 2;
        if (goCrit && bot.isOnGround()) {
            HeroBotAdapter.jump(server, session.botName);
            // Nur PLANNEN, nicht schlagen: der Angriff muss in der fallenden Phase landen
            // (siehe CRIT_FALL_DELAY_TICKS). Sprint bleibt beim Sprung an, damit der
            // Sprint-Jump 0.2 horizontale Velocity bekommt; gestoppt wird er beim Schlag.
            session.pendingCritAttackTick = session.tick + CRIT_FALL_DELAY_TICKS;
            return;
        }
        // HeroBot attack twice disables the shield with the first hit and follows up immediately.
        if (isBlocking(target)) {
            HeroBotAdapter.attackTwice(server, session.botName);
            session.lastManualAttackTick = session.tick;
            TrainingLog.event("SHIELD_STUN bot=" + session.botName + " target=" + session.targetName
                    + " distance=" + String.format(Locale.ROOT, "%.2f", distance)
                    + " weapon=" + itemPath(bot.getMainHandStack()));
            return;
        }
        // W-Tap-Pfad (kein Crit): Treffer MIT Sprint (Knockback-Bonus), Sprint DANACH
        // kurz loslassen, damit der naechste Treffer wieder ein Sprint-Schlag ist.
        // Vanilla erlaubt nicht beides — Sprint-KB und Crit schliessen sich aus.
        HeroBotAdapter.attackOnce(server, session.botName);
        session.meleeSwingCount++;
        session.lastManualAttackTick = session.tick;
        session.wTapUntilTick = session.tick + W_TAP_RELEASE_TICKS;
        HeroBotAdapter.setSprint(server, session.botName, false);
        TrainingLog.event("MELEE_ATTACK bot=" + session.botName + " target=" + session.targetName
                + " distance=" + String.format(Locale.ROOT, "%.2f", distance)
                + " weapon=" + itemPath(bot.getMainHandStack())
                + " wtap=true sprint_at_hit=true");
    }

    /**
     * Fuehrt den geplanten Crit-Schlag aus. Laeuft JEDES Tick vor dem Decision-Gate,
     * weil die fallende Phase nur ~6 Ticks dauert und das 5-Tick-Intervall sie sonst
     * nie trifft (belegt: 0.42 Jump-Velocity, 0.08 Schwerkraft pro Tick).
     */
    private static void tickCritAttack(MinecraftServer server, Session session,
                                       ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (session.pendingCritAttackTick == NO_PENDING_CRIT) {
            return;
        }
        if (session.rangedActive || session.retreating || session.healing) {
            // Zielwechsel auf Fernkampf/Rueckzug: der geplante Sprung-Schlag waere jetzt
            // falsch. Verwerfen, damit der naechste Melee-Zyklus neu plant.
            session.pendingCritAttackTick = NO_PENDING_CRIT;
            return;
        }
        if (session.tick < session.pendingCritAttackTick) {
            return; // noch aufsteigend
        }
        if (session.tick > session.pendingCritAttackTick + CRIT_FALL_DEADLINE_TICKS) {
            session.pendingCritAttackTick = NO_PENDING_CRIT; // Fenster verstrichen
            return;
        }
        double distance = bot.distanceTo(target);
        if (distance > MANUAL_ATTACK_DISTANCE || !bot.canSee(target)) {
            return; // Fenster bleibt offen bis zur Deadline
        }
        session.pendingCritAttackTick = NO_PENDING_CRIT;
        // Sprint VOR dem Treffer stoppen: Vanilla isCriticalHit() verlangt !isSprinting
        // (belegt: Pruefung "isSprinting == true -> return false" in PlayerEntity).
        // setSprint(false) wirkt sofort (BotPlayerActionPack.setSprinting ruft
        // player.setSprinting direkt auf), attackOnce erst im naechsten onUpdate.
        session.wTapUntilTick = session.tick + W_TAP_RELEASE_TICKS;
        HeroBotAdapter.setSprint(server, session.botName, false);
        HeroBotAdapter.attackOnce(server, session.botName);
        session.meleeSwingCount++;
        session.lastManualAttackTick = session.tick;
        // "crit_intent" statt "crit=true": wir steuern nur die Absicht (fallende Phase,
        // Sprint aus). Ob Vanilla zusaetzlich fallDistance > 0, kein Klettern, kein
        // Wasser, kein Blindheit erfuellt, ist aus dem Log nicht auslesbar.
        TrainingLog.event("MELEE_ATTACK bot=" + session.botName + " target=" + session.targetName
                + " distance=" + String.format(Locale.ROOT, "%.2f", distance)
                + " weapon=" + itemPath(bot.getMainHandStack())
                + " crit_intent=true on_ground=" + bot.isOnGround()
                + " sprint_at_hit=false");
    }

    private static boolean startHealing(MinecraftServer server, Session session, ServerPlayerEntity bot) {
        int appleSlot = -1;
        for (int slot = 0; slot < 36; slot++) {
            if (bot.getInventory().getStack(slot).isOf(Items.GOLDEN_APPLE)
                    || bot.getInventory().getStack(slot).isOf(Items.ENCHANTED_GOLDEN_APPLE)) {
                appleSlot = slot;
                break;
            }
        }
        if (appleSlot < 0) {
            return false;
        }
        if (appleSlot >= 9) {
            ItemStack apple = bot.getInventory().getStack(appleSlot).copy();
            ItemStack displaced = bot.getInventory().getStack(2).copy();
            bot.getInventory().setStack(appleSlot, displaced);
            bot.getInventory().setStack(2, apple);
            appleSlot = 2;
        }

        session.healReturnSlot = bot.getInventory().getSelectedSlot();
        session.healing = true;
        session.healingUntilTick = session.tick + HEAL_DURATION_TICKS;
        session.lastHealTick = session.tick;
        session.previousState = null;
        session.previousAction = null;
        if (session.shielding) {
            HeroBotAdapter.setUsing(server, session.botName, false);
            session.shielding = false;
        }
        execute(server, "player " + session.botName + " attack");
        execute(server, "player " + session.botName + " hotbar " + (appleSlot + 1));
        execute(server, "player " + session.botName + " use continuous");
        execute(server, "player " + session.botName + " move backward");
        execute(server, "player " + session.botName + " unsprint");
        TrainingLog.event("HEAL_START bot=" + session.botName + " health="
                + String.format(Locale.ROOT, "%.1f", bot.getHealth()));
        return true;
    }

    private static boolean startEating(MinecraftServer server, Session session, ServerPlayerEntity bot) {
        int foodSlot = -1;
        for (int slot = 0; slot < 36; slot++) {
            if (isFood(bot.getInventory().getStack(slot))) {
                foodSlot = slot;
                break;
            }
        }
        if (foodSlot < 0) {
            return false;
        }
        if (foodSlot >= 9) {
            ItemStack food = bot.getInventory().getStack(foodSlot).copy();
            ItemStack displaced = bot.getInventory().getStack(2).copy();
            bot.getInventory().setStack(foodSlot, displaced);
            bot.getInventory().setStack(2, food);
            foodSlot = 2;
        }

        session.healReturnSlot = bot.getInventory().getSelectedSlot();
        session.healing = true;
        session.healingUntilTick = session.tick + EAT_DURATION_TICKS;
        session.lastEatTick = session.tick;
        session.previousState = null;
        session.previousAction = null;
        if (session.shielding) {
            HeroBotAdapter.setUsing(server, session.botName, false);
            session.shielding = false;
        }
        execute(server, "player " + session.botName + " attack");
        execute(server, "player " + session.botName + " hotbar " + (foodSlot + 1));
        execute(server, "player " + session.botName + " use continuous");
        execute(server, "player " + session.botName + " move backward");
        execute(server, "player " + session.botName + " unsprint");
        TrainingLog.event("EAT_START bot=" + session.botName + " item="
                + itemPath(bot.getInventory().getStack(foodSlot))
                + " hunger=" + bot.getHungerManager().getFoodLevel());
        return true;
    }

    private static boolean isFood(ItemStack stack) {
        return !stack.isEmpty() && FOOD_PATHS.contains(itemPath(stack));
    }

    /** True when the entity currently holds a shield in use. */
    private static boolean isBlocking(ServerPlayerEntity entity) {
        return entity.isUsingItem() && entity.getActiveItem().isOf(Items.SHIELD);
    }

    /** True when an axe sits in the first nine inventory slots. */
    private static boolean hasAxe(ServerPlayerEntity player) {
        for (int slot = 0; slot < 9; slot++) {
            if (itemPath(player.getInventory().getStack(slot)).endsWith("_axe")) {
                return true;
            }
        }
        return false;
    }

    private static String humanState(Session session, ServerPlayerEntity bot, ServerPlayerEntity target) {
        return session.policy.observe(bot.distanceTo(target), target.getHealth(), bot.getHealth(),
                bot.isOnGround(), isBlocking(bot), hasAxe(target));
    }

    /**
     * Lernen aus dem Spiel des menschlichen Gegners. Es fließt nur ein, was gut war:
     * ausgeteilter Schaden gegen den Bot und der Sieg. Fehler des Spielers werden nie bestraft.
     */
    private static void learnFromHuman(Session session, ServerPlayerEntity bot, ServerPlayerEntity target,
                                       double humanReward, boolean terminal) {
        String nextState = terminal ? null : humanState(session, bot, target);
        double clampedReward = Math.max(0.0, humanReward);
        QLearningPolicy.Action intervalAction = bot == null || target == null
                ? QLearningPolicy.Action.HOLD
                : inferHumanAction(bot, target, session.humanLastPos);
        if (session.humanPreviousState != null) {
            session.policy.update(session.humanPreviousState, intervalAction,
                    clampedReward, nextState, terminal);
            if (clampedReward >= 1.0 || terminal) {
                TrainingLog.event("HUMAN_LEARN target=" + session.targetName
                        + " reward=" + String.format(Locale.ROOT, "%.2f", clampedReward)
                        + " terminal=" + terminal);
            }
        }
        if (terminal) {
            return;
        }
        session.humanPreviousState = nextState;
        session.humanLastPos = target.getEntityPos();
    }

    /** Leitet aus der Bewegung des Spielers die passende Q-Aktion ab; die erste Beobachtung ist HOLD. */
    private static QLearningPolicy.Action inferHumanAction(ServerPlayerEntity bot, ServerPlayerEntity target,
                                                           Vec3d previous) {
        if (previous == null) {
            return QLearningPolicy.Action.HOLD;
        }
        Vec3d moved = target.getEntityPos().subtract(previous);
        if (moved.horizontalLength() < HUMAN_MIN_MOVE) {
            return QLearningPolicy.Action.HOLD;
        }
        double dx = bot.getX() - previous.x;
        double dz = bot.getZ() - previous.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.001) {
            return QLearningPolicy.Action.HOLD;
        }
        double forward = (moved.x * dx + moved.z * dz) / horizontal;
        double side = (-moved.x * dz + moved.z * dx) / horizontal;
        if (forward > HUMAN_FORWARD_STEP) return QLearningPolicy.Action.APPROACH;
        if (forward < -HUMAN_FORWARD_STEP) return QLearningPolicy.Action.RETREAT;
        if (side > HUMAN_SIDE_STEP) return QLearningPolicy.Action.STRAFE_RIGHT;
        if (side < -HUMAN_SIDE_STEP) return QLearningPolicy.Action.STRAFE_LEFT;
        return QLearningPolicy.Action.HOLD;
    }

    /** True when the target faces the bot and is close enough to land a hit right now. */
    private static boolean enemyPressure(ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (target == null || bot.distanceTo(target) > EAT_SAFE_DISTANCE) {
            return false;
        }
        Vec3d toBot = new Vec3d(bot.getX() - target.getX(), 0.0, bot.getZ() - target.getZ()).normalize();
        Vec3d targetFacing = target.getRotationVec(1.0F);
        targetFacing = new Vec3d(targetFacing.x, 0.0, targetFacing.z).normalize();
        return targetFacing.dotProduct(toBot) >= 0.45;
    }

    private static void finishHealing(MinecraftServer server, Session session, ServerPlayerEntity bot) {
        execute(server, "player " + session.botName + " use");
        HeroBotAdapter.stopMovement(server, session.botName);
        int returnSlot = findCombatSlot(bot, session.healReturnSlot);
        execute(server, "player " + session.botName + " hotbar " + (returnSlot + 1));
        // Siehe Start: kein attack continuous — der Angriff kommt aus tryCritMelee.
        session.healing = false;
        TrainingLog.event("HEAL_END bot=" + session.botName + " health="
                + String.format(Locale.ROOT, "%.1f", bot.getHealth()));
    }

    private static int findCombatSlot(ServerPlayerEntity bot, int fallback) {
        int weaponSlot = chooseWeaponSlot(bot, null);
        if (weaponSlot >= 0) return weaponSlot;
        return Math.max(0, Math.min(8, fallback));
    }

    private static void recordHealingTick(Session session, ServerPlayerEntity bot,
                                          ServerPlayerEntity target, String state, double reward) {
        TrainingLog.record(session.tick, session.botName, session.targetName, state,
                QLearningPolicy.Action.HOLD, reward, false);
        session.lastBotHealth = bot.getHealth();
        session.lastTargetHealth = target.getHealth();
        session.decisions++;
        if (session.decisions % 20 == 0) {
            session.policy.save();
        }
    }

    private static void finish(Session session, boolean removeBot, String reason) {
        if (active != session) {
            return;
        }
        execute(session.server, "player " + session.botName + " stop");
        execute(session.server, "player " + session.botName + " autojump false");
        if (removeBot) {
            execute(session.server, "player " + session.botName + " disconnect");
        }
        session.policy.save();
        TrainingLog.event("END bot=" + session.botName + " target=" + session.targetName
                + " reason=" + reason.replace(' ', '_') + " decisions=" + session.decisions
                + " q_updates=" + session.policy.updates());
        ServerPlayerEntity target = HeroBotAdapter.findPlayer(session.server, session.targetName);
        if (target != null) {
            target.sendMessage(Text.literal("PvP-Training beendet: " + reason + " · "
                    + session.policy.updates() + " Q-Lernschritte gespeichert."), false);
        }
        active = null;
    }

    /**
     * Fuehrt einen Befehl aus und protokolliert Syntaxfehler.
     * parseAndExecute() faengt Syntaxfehler intern und zeigt sie nur als roten Chat-Fehler an,
     * ohne eine Exception zu werfen. Deshalb wird der Brigadier-Dispatcher direkt genutzt:
     * execute(String, S) wirft bei verworfener Syntax eine CommandSyntaxException.
     */
    private static void execute(MinecraftServer server, String command) {
        ServerCommandSource source = server.getCommandSource().withSilent();
        try {
            server.getCommandManager().getDispatcher().execute(command, source);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException syntaxError) {
            HeroBotAiCommon.LOGGER.error("Command syntax rejected: {} :: {}", command, syntaxError.getMessage());
            TrainingLog.error("Command syntax rejected: " + command + " :: " + syntaxError.getMessage());
        } catch (Throwable exception) {
            HeroBotAiCommon.LOGGER.error("HeroBot command failed: {}", command, exception);
            TrainingLog.error("HeroBot command failed: " + command + " :: " + exception);
        }
    }

    private static String coordinate(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static final class PendingStart {
        private final MinecraftServer server;
        private final ServerCommandSource source;
        private final String botName;
        private final String targetName;
        private final PvPKit kit;
        private long ticks;
        private boolean cancelled;

        private PendingStart(MinecraftServer server, ServerCommandSource source, String botName,
                             String targetName, PvPKit kit) {
            this.server = server;
            this.source = source;
            this.botName = botName;
            this.targetName = targetName;
            this.kit = kit;
        }
    }

    private static final class Session {
        private final MinecraftServer server;
        private final String botName;
        private final String targetName;
        private final QLearningPolicy policy;
        private final PvPKit kit;
        private long tick;
        private long decisions;
        private long lastPearlTick = -PEARL_COOLDOWN_TICKS;
        private long lastHealTick = -HEAL_COOLDOWN_TICKS;
        private long lastEatTick = -EAT_COOLDOWN_TICKS;
        private boolean retreating;
        private long healingUntilTick;
        private int healReturnSlot;
        private boolean healing;
        private boolean shielding;
        private boolean maceLoadout;
        private MacePhase macePhase = MacePhase.DONE;
        private int macePhaseTicks;
        private int maceBoosts;
        private long maceBoostTick;
        private long lastMaceOpenerTick = -MACE_OPENER_COOLDOWN_TICKS;
        private float maceInitialBotHealth;
        private float maceInitialTargetHealth;
        private String maceState;
        private long lastBotDamageTick = Long.MIN_VALUE / 2;
        private long lastTargetHitTick;
        private long lastCombatDiagnosticTick = Long.MIN_VALUE / 2;
        private long lastShieldRaiseTick = Long.MIN_VALUE / 2;
        private long lastManualAttackTick = Long.MIN_VALUE / 2;
        private long lastCrystalTick = Long.MIN_VALUE / 2;
        private CrystalPhase crystalPhase = CrystalPhase.IDLE;
        /** Tick, ab dem die naechste Crystal-Phase laeuft (Aktionen muessen auseinanderliegen). */
        private long crystalNextTick;
        private Vec3d crystalBase;
        private int crystalReturnSlot = -1;
        /**
         * Slot-Restauration auf den Folgetick. HeroBot wertet Befehle erst im naechsten Tick
         * aus: wer im selben Tick zuruecktauscht, verhindert, dass die Aktion ueberhaupt wirkt
         * (belegt: CRYSTAL_OBI_PLACE confirmed=false, Splash-Potion nie geworfen).
         */
        private int pendingRestoreSlotA = -1;
        private ItemStack pendingRestoreStackA;
        private int pendingRestoreSlotB = -1;
        private ItemStack pendingRestoreStackB;
        private int pendingHotbarRestore = -1;
        /** Splash-Potion: Ruecktausch von Potion und alter Hand erst im Folgetick. */
        private boolean pendingSplashRestore;
        private int splashPotionSlot = -1;
        private int splashHeldSlot = -1;
        private ItemStack splashSavedHeld;
        private long pendingCritAttackTick = NO_PENDING_CRIT;
        private long lastWindChargeTick = Long.MIN_VALUE / 2;
        private long lastSplashBuffTick = Long.MIN_VALUE / 2;
        private long lastSplashDamageTick = Long.MIN_VALUE / 2;
        /** W-Tap: bis zu diesem Tick Sprint nach einem Treffer aus -> jeder Hit wird Sprint-KB-Angriff. */
        private long wTapUntilTick;
        private boolean rangedActive;
        private int meleeSwingCount;
        private String previousState;
        private QLearningPolicy.Action previousAction;
        private String humanPreviousState;
        private Vec3d humanLastPos;
        private float lastBotHealth;
        private float lastTargetHealth;

        private Session(MinecraftServer server, String botName, String targetName,
                        QLearningPolicy policy, PvPKit kit) {
            this.server = server;
            this.botName = botName;
            this.targetName = targetName;
            this.policy = policy;
            this.kit = kit;
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);
            ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetName);
            this.lastBotHealth = bot == null ? 20.0F : bot.getHealth();
            this.lastTargetHealth = target == null ? 20.0F : target.getHealth();
        }
    }

    private enum MacePhase {
        WAIT_GLIDE,
        CLIMB,
        DIVE,
        DONE
    }

    /**
     * Crystal-Blast verteilt ueber mehrere Ticks. Belegt im Log: vorher wurden Slot-Wechsel,
     * zwei "use once" und ein "attack once" in EINEM Tick gestapelt — HeroBot haelt den alten
     * Slot und den alten Blick, die Obsidian-Basis war "confirmed=false" und der Crystal wurde
     * auf nichts gelegt. Jede Phase braucht mindestens einen eigenen Tick.
     */
    private enum CrystalPhase {
        IDLE,
        /** Obsidian wurde geworfen; naechster Tick prueft die Bestaetigung. */
        OBIS_PLACED,
        /** Crystal wurde gesetzt; naechster Tick zundet. */
        CRYSTAL_PLACED,
        DONE
    }
}
