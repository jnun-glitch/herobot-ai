package de.codex.herobotai;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.Items;
import net.minecraft.item.CrossbowItem;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

final class TrainingController {
    private static final int DECISION_INTERVAL_TICKS = 5;
    private static final double MELEE_DISTANCE = 2.45;
    private static final double MANUAL_ATTACK_DISTANCE = 2.75;
    /** Mindester-Angriffs-Abstand in Ticks (W-Tap-Grundrhythmus). Waffen-Cooldowns prüft weaponCooldownTicks(). */
    private static final long MANUAL_ATTACK_INTERVAL_TICKS = 9;
    /** Nach jedem Treffer Sprint 2 Ticks loslassen -> jeder Hit wird Sprint-KB-Angriff (W-Tap). */
    private static final long W_TAP_RELEASE_TICKS = 2;
    /** Voller Bogen-Zug ohne Quick Charge: 20 Ticks. */
    private static final long BOW_DRAW_TICKS = 20;
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
    private static final double RETREAT_SAFE_DISTANCE = 7.0;
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
    private static final long XP_BOTTLE_COOLDOWN_TICKS = 20;
    private static final double XP_MEND_SAFE_DISTANCE = 8.0;
    private static final long MOVEMENT_PROGRESS_INTERVAL_TICKS = 5;
    private static final long OBSTACLE_ESCAPE_TICKS = 12;
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
        HeroBotAdapter.CombatSettingsSnapshot previousCombatSettings =
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
            HeroBotAdapter.restoreCombatSettings(server, previousCombatSettings);
            source.sendError(Text.literal("Das Kit " + kit.commandName() + " konnte nicht ausgerüstet werden. Prüfe HeroBot-Serverlogs."));
            TrainingLog.error("Duel kit setup failed bot=" + botName + " kit=" + kit.commandName());
            return;
        }
        QLearningPolicy policy = new QLearningPolicy(kit);
        active = new Session(server, botName, targetName, policy, kit, previousCombatSettings);
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
        maintainTotemSafety(server, session, bot);
        if (session.macePhase != MacePhase.DONE) {
            tickMaceOpener(session, bot, target);
            return;
        }
        // Crystal-Phasen laufen unabhaengig vom Decision-Intervall weiter.
        tickCrystalBlast(server, session, bot, target);
        // Crit-Nachschlag laeuft ebenfalls jeden Tick: die fallende Phase des Sprungs
        // dauert nur ~6 Ticks, das 5-Tick-Intervall waere sonst nie getroffen.
        tickCritAttack(server, session, bot, target);
        tickSprintReset(server, session);
        tickRangedCombat(server, session, bot, target);
        if (session.tick % DECISION_INTERVAL_TICKS != 0) {
            return;
        }

        String state = combatState(session, bot, target);
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
        // Retreat ends only after health has recovered. Reaching a safe distance pauses movement,
        // so the bot can heal instead of immediately running back into the opponent.
        if (bot.getHealth() <= RETREAT_HEALTH_THRESHOLD) {
            if (!session.retreating) {
                session.retreating = true;
                TrainingLog.event("RETREAT_START bot=" + session.botName + " health="
                        + String.format(Locale.ROOT, "%.1f", bot.getHealth()));
            }
        } else if (session.retreating && bot.getHealth() >= RETREAT_END_HEALTH) {
            session.retreating = false;
            TrainingLog.event("RETREAT_END bot=" + session.botName + " health="
                    + String.format(Locale.ROOT, "%.1f", bot.getHealth())
                    + " distance=" + String.format(Locale.ROOT, "%.2f", distance));
        }

        if (!session.healing && !enemyPressure(bot, target)
                && bot.getHealth() > RETREAT_HEALTH_THRESHOLD
                && tryMendingExperienceBottle(server, session, bot, target)) {
            recordHealingTick(session, bot, target, state, reward);
            return;
        }
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
        // Exploration and stale Q-values must not make a healthy bot flee at random.
        // Low-health retreat is handled explicitly by the retreat state below.
        if (!session.retreating && action == QLearningPolicy.Action.RETREAT
                && bot.getHealth() > RETREAT_HEALTH_THRESHOLD + 4.0F) {
            action = distance <= MELEE_DISTANCE
                    ? (session.tick % 2 == 0 ? QLearningPolicy.Action.STRAFE_LEFT
                    : QLearningPolicy.Action.STRAFE_RIGHT)
                    : QLearningPolicy.Action.APPROACH;
        }
        boolean targetBlocking = isBlocking(target);
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
        if (!rangedActive && session.crystalPhase == CrystalPhase.IDLE) {
            tryPearl(server, session, bot, target);
        }
        if (action == QLearningPolicy.Action.RETREAT && distance < RETREAT_SAFE_DISTANCE && !rangedActive
                && session.crystalPhase == CrystalPhase.IDLE) {
            tryWindCharge(server, session, bot);
        }
        if (!rangedActive) {
            tryCrystalBlast(server, session, bot, target);
        }
        boolean crystalBusy = session.crystalPhase != CrystalPhase.IDLE;
        boolean nativePathing = updatePathing(server, session, bot, target, action,
                !rangedActive && !session.healing && !crystalBusy
                        && session.macePhase == MacePhase.DONE);
        boolean obstacleEscaping = !nativePathing && session.obstacleEscapeUntilTick >= session.tick;
        if (obstacleEscaping) {
            action = session.obstacleEscapeLeft
                    ? QLearningPolicy.Action.STRAFE_LEFT : QLearningPolicy.Action.STRAFE_RIGHT;
        }
        if (crystalBusy) {
            // Phase actions need to keep their selected slot and view until HeroBot handles use once.
            HeroBotAdapter.stopMovement(server, session.botName);
            HeroBotAdapter.setSprint(server, session.botName, false);
            action = QLearningPolicy.Action.HOLD;
        } else if (!nativePathing) {
            if (obstacleEscaping) {
                applyObstacleEscape(server, session, bot, target);
            } else {
                applyAction(server, session, bot, target, action);
            }
            if (!obstacleEscaping && (action == QLearningPolicy.Action.STRAFE_LEFT
                    || action == QLearningPolicy.Action.STRAFE_RIGHT)) {
                lookAtCombatTarget(server, session, target);
            }
        }
        if (!rangedActive && !crystalBusy && session.itemLease == null) {
            tryCritMelee(server, session, bot, target, distance, action);
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

    /** Uses HeroBot pathfinding for obstructed pursuit and detects direct movement stuck at walls. */
    private static boolean updatePathing(MinecraftServer server, Session session,
                                         ServerPlayerEntity bot, ServerPlayerEntity target,
                                         QLearningPolicy.Action action, boolean allowed) {
        boolean advancing = action == QLearningPolicy.Action.APPROACH
                || action == QLearningPolicy.Action.STRAFE_LEFT
                || action == QLearningPolicy.Action.STRAFE_RIGHT;
        boolean retreating = action == QLearningPolicy.Action.RETREAT;
        double distance = bot.distanceTo(target);
        boolean monitorMovement = allowed && ((advancing && distance > 2.5)
                || (retreating && distance < RETREAT_SAFE_DISTANCE));
        if (!monitorMovement) {
            resetMovementTracking(server, session);
            return false;
        }

        if (session.tick - session.lastMovementCheckTick >= MOVEMENT_PROGRESS_INTERVAL_TICKS) {
            Vec3d position = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
            if (session.lastMovementCheckPosition != null
                    && position.squaredDistanceTo(session.lastMovementCheckPosition) < 0.09) {
                session.stuckChecks++;
            } else {
                session.stuckChecks = 0;
                session.pathRetries = 0;
            }
            session.lastMovementCheckPosition = position;
            session.lastMovementCheckTick = session.tick;
        }

        boolean blocked = advancing && !bot.canSee(target);
        boolean escapeInProgress = session.obstacleEscapeUntilTick >= session.tick;
        boolean canPathToTarget = advancing;
        boolean needsPath = session.nativePathSupported && canPathToTarget && !escapeInProgress
                && (blocked || session.stuckChecks >= 2);
        if (session.pathing && !needsPath) {
            HeroBotAdapter.stopPathing(server, session.botName);
            session.pathing = false;
            session.stuckChecks = 0;
        }
        if (needsPath && !session.pathing) {
            HeroBotAdapter.pathToEntity(server, session.botName, session.targetName);
            session.pathing = true;
            session.lastPathCommandTick = session.tick;
            TrainingLog.event("PATH_START bot=" + session.botName + " target=" + session.targetName
                    + " reason=" + (blocked ? "occluded" : "stuck"));
            return true;
        }

        if (session.pathing) {
            if (session.stuckChecks >= 2 && session.tick - session.lastPathCommandTick >= 20) {
                session.pathRetries++;
                if (session.pathRetries >= 3) {
                    HeroBotAdapter.stopPathing(server, session.botName);
                    session.pathing = false;
                    session.obstacleEscapeUntilTick = session.tick + OBSTACLE_ESCAPE_TICKS;
                    session.obstacleEscapeLeft = chooseObstacleEscapeLeft(bot, target, session.pathRetries++);
                    session.stuckChecks = 0;
                    TrainingLog.event("PATH_FALLBACK bot=" + session.botName
                            + " target=" + session.targetName + " retries=" + session.pathRetries);
                    if (bot.isOnGround()) HeroBotAdapter.jump(server, session.botName);
                    return false;
                }
                HeroBotAdapter.stopPathing(server, session.botName);
                HeroBotAdapter.pathToEntity(server, session.botName, session.targetName);
                session.lastPathCommandTick = session.tick;
                session.stuckChecks = 0;
                TrainingLog.event("PATH_RETRY bot=" + session.botName + " target=" + session.targetName);
            }
            return true;
        }

        if ((!session.nativePathSupported || !canPathToTarget) && session.stuckChecks >= 2) {
            session.obstacleEscapeUntilTick = session.tick + OBSTACLE_ESCAPE_TICKS;
            session.obstacleEscapeLeft = chooseObstacleEscapeLeft(bot, target, session.pathRetries++);
            session.stuckChecks = 0;
            TrainingLog.event("OBSTACLE_ESCAPE bot=" + session.botName
                    + " target=" + session.targetName
                    + " direction=" + (session.obstacleEscapeLeft ? "left" : "right"));
            if (bot.isOnGround()) HeroBotAdapter.jump(server, session.botName);
        }
        return false;
    }

    /** Chooses a side whose next player-sized step is clear, preferring alternation if both fit. */
    private static boolean chooseObstacleEscapeLeft(ServerPlayerEntity bot, ServerPlayerEntity target,
                                                     int attempt) {
        Vec3d towardTarget = new Vec3d(target.getX() - bot.getX(), 0.0,
                target.getZ() - bot.getZ()).normalize();
        if (towardTarget.lengthSquared() < 1.0E-4) {
            Vec3d facing = bot.getRotationVec(1.0F);
            towardTarget = new Vec3d(facing.x, 0.0, facing.z).normalize();
        }
        Vec3d left = new Vec3d(towardTarget.z, 0.0, -towardTarget.x);
        Vec3d right = left.multiply(-1.0);
        boolean leftClear = hasHorizontalClearance(bot, left);
        boolean rightClear = hasHorizontalClearance(bot, right);
        if (leftClear != rightClear) return leftClear;
        return attempt % 2 == 0;
    }

    private static boolean hasHorizontalClearance(ServerPlayerEntity bot, Vec3d direction) {
        double nextX = bot.getX() + direction.x * 0.75;
        double nextZ = bot.getZ() + direction.z * 0.75;
        Box nextStep = bot.getBoundingBox().offset(direction.x * 0.75, 0.0, direction.z * 0.75);
        if (!bot.getEntityWorld().isSpaceEmpty(bot, nextStep)) return false;
        if (bot.isOnGround()) {
            BlockPos support = BlockPos.ofFloored(nextX, bot.getY() - 0.05, nextZ);
            var state = bot.getEntityWorld().getBlockState(support);
            return state.isSolidBlock(bot.getEntityWorld(), support);
        }
        return true;
    }

    /** Steps sideways without pushing forward into the same wall that caused the stall. */
    private static void applyObstacleEscape(MinecraftServer server, Session session,
                                            ServerPlayerEntity bot, ServerPlayerEntity target) {
        lookAtCombatTarget(server, session, target);
        HeroBotAdapter.stopMovement(server, session.botName);
        HeroBotAdapter.setSprint(server, session.botName, false);
        HeroBotAdapter.move(server, session.botName, session.obstacleEscapeLeft ? "left" : "right");
        if (bot.isOnGround()) HeroBotAdapter.jump(server, session.botName);
    }

    private static void resetMovementTracking(MinecraftServer server, Session session) {
        if (session.pathing) HeroBotAdapter.stopPathing(server, session.botName);
        session.pathing = false;
        session.stuckChecks = 0;
        session.pathRetries = 0;
        session.lastMovementCheckPosition = null;
        session.lastMovementCheckTick = session.tick;
        session.obstacleEscapeUntilTick = Long.MIN_VALUE / 2;
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

    /** Sprint-Reset erst nach dem tick ausfuehren, in dem HeroBot den Schlag verarbeitet hat. */
    private static void tickSprintReset(MinecraftServer server, Session session) {
        if (session.pendingSprintReleaseTick != NO_PENDING_CRIT
                && session.tick >= session.pendingSprintReleaseTick) {
            HeroBotAdapter.setSprint(server, session.botName, false);
            session.pendingSprintReleaseTick = NO_PENDING_CRIT;
            session.wTapNeedsRestart = true;
        }
        if (!session.wTapNeedsRestart || session.tick < session.wTapUntilTick
                || session.rangedActive || session.retreating || session.healing
                || session.pendingCritAttackTick != NO_PENDING_CRIT
                || session.crystalPhase != CrystalPhase.IDLE
                || session.macePhase != MacePhase.DONE) {
            return;
        }
        HeroBotAdapter.setSprint(server, session.botName, true);
        session.wTapNeedsRestart = false;
    }

    /**
     * W-Tap: Sprint bleibt bis zum ausgefuehrten Treffer an. Danach bleibt der Bot kurz
     * unsprintend; nur Vorwaertsbewegung startet Sprint wieder.
     */
    private static void sprintIfAllowed(MinecraftServer server, Session session, String bot) {
        if (session.tick > session.wTapUntilTick) {
            execute(server, "player " + bot + " sprint");
            session.wTapNeedsRestart = false;
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
                if (botEntity.distanceTo(targetEntity) >= RETREAT_SAFE_DISTANCE) {
                    HeroBotAdapter.stopMovement(server, bot);
                    execute(server, "player " + bot + " unsprint");
                    return;
                }
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
                if (!session.rangedActive) {
                    execute(server, "player " + bot + (session.tick % 2 == 0 ? " move left" : " move right"));
                }
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

    /** Replaces a popped totem immediately and double-hands a reserve only during lethal danger. */
    private static void maintainTotemSafety(MinecraftServer server, Session session, ServerPlayerEntity bot) {
        boolean recentDamage = session.tick - session.lastBotDamageTick <= 10;
        boolean totemPriority = session.kit == PvPKit.CRYSTAL || bot.getHealth() <= HEAL_HEALTH_THRESHOLD
                || recentDamage;
        if (totemPriority && !bot.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            boolean replaced = moveTotemToOffhand(bot);
            if (replaced) {
                if (session.shielding) {
                    HeroBotAdapter.setUsing(server, session.botName, false);
                    session.shielding = false;
                }
                TrainingLog.event("TOTEM_OFFHAND bot=" + session.botName + " replacement=true");
            }
        }

        boolean lethalDanger = bot.getHealth() <= 5.0F
                || (bot.getHealth() <= 8.0F && recentDamage);
        if (lethalDanger && !session.totemDoubleHandLease && session.itemLease == null
                && !session.healing && !session.rangedActive) {
            ItemLease lease = equipItemForHeroBot(server, session, bot,
                    stack -> stack.isOf(Items.TOTEM_OF_UNDYING));
            if (lease != null) {
                session.itemLeaseRestoreTick = Long.MAX_VALUE;
                session.totemDoubleHandLease = true;
                TrainingLog.event("TOTEM_DOUBLE_HAND bot=" + session.botName
                        + " health=" + String.format(Locale.ROOT, "%.1f", bot.getHealth()));
            }
        } else if (!lethalDanger && session.totemDoubleHandLease) {
            restoreItemLease(server, session, bot);
            session.totemDoubleHandLease = false;
        }
    }

    private static void updateShield(Session session, ServerPlayerEntity bot, ServerPlayerEntity target) {
        boolean totemPriority = session.kit == PvPKit.CRYSTAL
                || bot.getHealth() <= HEAL_HEALTH_THRESHOLD
                || session.tick - session.lastBotDamageTick <= 10;
        boolean hasTotem = bot.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
                || (totemPriority && moveTotemToOffhand(bot));
        boolean hasShield = !hasTotem && moveShieldToOffhand(bot);
        if (!hasTotem && !hasShield) hasTotem = moveTotemToOffhand(bot);
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
        if (session.retreating || session.healing || session.rangedActive || session.itemLease != null) {
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
        session.maceState = combatState(session, bot, target);
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
                    aimClimb(server, session, bot, target);
                    if (!useItemOnce(server, session, bot, Items.FIREWORK_ROCKET)) {
                        finishMaceOpener(session, bot, target, "no_rocket_available");
                        return;
                    }
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
                        && findInventorySlot(bot, stack -> stack.isOf(Items.FIREWORK_ROCKET)) >= 0) {
                    if (useItemOnce(server, session, bot, Items.FIREWORK_ROCKET)) {
                        session.maceBoosts++;
                        session.maceBoostTick = session.tick;
                        TrainingLog.event("MACE_BOOST bot=" + session.botName + " count=" + session.maceBoosts);
                    }
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
        ItemLease maceLease = equipItemForHeroBot(session.server, session, bot, stack -> stack.isOf(Items.MACE));
        if (maceLease == null) {
            finishMaceOpener(session, bot, target, "mace_unavailable");
            return;
        }
        session.itemLeaseRestoreTick = Long.MAX_VALUE;
        session.macePhase = MacePhase.DIVE;
        session.macePhaseTicks = 0;
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
        String nextState = combatState(session, bot, target);
        session.policy.update(session.maceState, QLearningPolicy.Action.APPROACH, reward, nextState, false);
        TrainingLog.record(session.tick, session.botName, session.targetName, session.maceState,
                QLearningPolicy.Action.APPROACH, reward, false);
        session.macePhase = MacePhase.DONE;
        restoreItemLease(session.server, session, bot);
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
        if (session.rangedActive) {
            if (session.kit == PvPKit.CRYSTAL || session.retreating
                    || bot.getHealth() <= RETREAT_HEALTH_THRESHOLD) {
                cancelRangedCombat(server, session, "crystal_or_retreat");
                return false;
            }
            return true;
        }
        if (session.kit == PvPKit.CRYSTAL || bot.getHealth() <= RETREAT_HEALTH_THRESHOLD
                || distance < RANGED_MIN_DISTANCE || distance > RANGED_MAX_DISTANCE || !bot.canSee(target)
                || session.retreating) {
            return false;
        }

        int slot = findChargedCrossbowSlot(bot);
        if (slot >= 0) {
            return startCrossbowShot(server, session, bot, slot, distance);
        }

        int bowSlot = findInventorySlot(bot, stack -> stack.isOf(Items.BOW));
        if (bowSlot >= 0 && hasArrows(bot)) {
            ItemLease lease = equipItemAtSlot(server, session, bot, bowSlot);
            if (lease == null) return false;
            session.rangedSlot = lease.hotbarSlot();
            session.itemLeaseRestoreTick = Long.MAX_VALUE;
            session.rangedActive = true;
            session.rangedPhase = RangedPhase.BOW_DRAWING;
            session.rangedNextTick = session.tick + BOW_DRAW_TICKS;
            HeroBotAdapter.setUsing(server, session.botName, true);
            logRangedStart(session, bot, session.rangedSlot, distance, "BOW_DRAW");
            return true;
        }

        int crossbowSlot = findInventorySlot(bot, stack -> stack.isOf(Items.CROSSBOW));
        if (crossbowSlot >= 0 && hasCrossbowProjectiles(bot)) {
            ItemLease lease = equipItemAtSlot(server, session, bot, crossbowSlot);
            if (lease == null) return false;
            session.rangedSlot = lease.hotbarSlot();
            session.itemLeaseRestoreTick = Long.MAX_VALUE;
            session.rangedActive = true;
            session.rangedPhase = RangedPhase.CROSSBOW_LOADING;
            HeroBotAdapter.setUsing(server, session.botName, true);
            logRangedStart(session, bot, session.rangedSlot, distance, "CROSSBOW_LOAD");
            return true;
        }
        return false;
    }

    /** Fuehrt Bogen- und Armbrust-Zyklen pro tick aus: laden, freigeben, schiessen, nachladen. */
    private static void tickRangedCombat(MinecraftServer server, Session session,
                                         ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (!session.rangedActive) {
            return;
        }
        double distance = bot.distanceTo(target);
        if (bot.getHealth() <= RETREAT_HEALTH_THRESHOLD || distance <= RANGED_STOP_DISTANCE
                || distance > RANGED_MAX_DISTANCE || !bot.canSee(target)
                || session.retreating
                || session.rangedSlot < 0 || bot.getInventory().getSelectedSlot() != session.rangedSlot) {
            stopRangedCombat(server, session, distance);
            return;
        }

        ItemStack held = bot.getInventory().getStack(session.rangedSlot);
        switch (session.rangedPhase) {
            case BOW_DRAWING -> {
                if (!held.isOf(Items.BOW)) {
                    stopRangedCombat(server, session, distance);
                } else if (session.tick >= session.rangedNextTick) {
                    // BowItem schiesst beim Ende des Haltens; ein use continuous ohne Release laedt nur.
                    HeroBotAdapter.setUsing(server, session.botName, false);
                    session.rangedPhase = RangedPhase.BOW_RELOAD_WAIT;
                    session.rangedNextTick = session.tick + 2;
                    TrainingLog.event("RANGE_FIRE bot=" + session.botName + " item=bow");
                }
            }
            case BOW_RELOAD_WAIT -> {
                if (session.tick >= session.rangedNextTick) {
                    if (hasArrows(bot)) {
                        HeroBotAdapter.setUsing(server, session.botName, true);
                        session.rangedPhase = RangedPhase.BOW_DRAWING;
                        session.rangedNextTick = session.tick + BOW_DRAW_TICKS;
                    } else {
                        stopRangedCombat(server, session, distance);
                    }
                }
            }
            case CROSSBOW_LOADING -> {
                if (!held.isOf(Items.CROSSBOW)) {
                    stopRangedCombat(server, session, distance);
                } else if (CrossbowItem.isCharged(held)) {
                    HeroBotAdapter.setUsing(server, session.botName, false);
                    session.rangedPhase = RangedPhase.CROSSBOW_FIRE_PENDING;
                    session.rangedNextTick = session.tick + 1;
                    session.rangedRetries = 0;
                    TrainingLog.event("RANGE_LOADED bot=" + session.botName + " item=crossbow");
                } else if (!hasCrossbowProjectiles(bot)) {
                    stopRangedCombat(server, session, distance);
                }
            }
            case CROSSBOW_FIRE_PENDING -> {
                if (session.tick >= session.rangedNextTick) {
                    HeroBotAdapter.useOnce(server, session.botName);
                    session.rangedPhase = RangedPhase.CROSSBOW_VERIFY;
                    session.rangedNextTick = session.tick + 1;
                }
            }
            case CROSSBOW_VERIFY -> {
                if (session.tick >= session.rangedNextTick) {
                    if (!CrossbowItem.isCharged(held)) {
                        session.rangedPhase = RangedPhase.CROSSBOW_RELOAD_WAIT;
                        session.rangedNextTick = session.tick + 1;
                        TrainingLog.event("RANGE_FIRE bot=" + session.botName + " item=crossbow");
                    } else if (session.rangedRetries++ == 0) {
                        // HeroBot verarbeitet use once im naechsten Bot-Tick; ein Retry faengt
                        // einen verpassten Slot-/Use-Tick ab, ohne endlos am selben Schuss zu haengen.
                        HeroBotAdapter.useOnce(server, session.botName);
                        session.rangedNextTick = session.tick + 1;
                    } else {
                        stopRangedCombat(server, session, distance);
                    }
                }
            }
            case CROSSBOW_RELOAD_WAIT -> {
                if (session.tick >= session.rangedNextTick) {
                    if (hasCrossbowProjectiles(bot)) {
                        HeroBotAdapter.setUsing(server, session.botName, true);
                        session.rangedPhase = RangedPhase.CROSSBOW_LOADING;
                    } else {
                        stopRangedCombat(server, session, distance);
                    }
                }
            }
            case IDLE -> stopRangedCombat(server, session, distance);
        }
    }

    private static boolean startCrossbowShot(MinecraftServer server, Session session,
                                             ServerPlayerEntity bot, int slot, double distance) {
        ItemLease lease = equipItemAtSlot(server, session, bot, slot);
        if (lease == null) return false;
        HeroBotAdapter.setUsing(server, session.botName, false);
        session.rangedSlot = lease.hotbarSlot();
        session.itemLeaseRestoreTick = Long.MAX_VALUE;
        session.rangedActive = true;
        session.rangedPhase = RangedPhase.CROSSBOW_FIRE_PENDING;
        session.rangedNextTick = session.tick + 1;
        session.rangedRetries = 0;
        logRangedStart(session, bot, session.rangedSlot, distance, "CROSSBOW_FIRE");
        return true;
    }

    private static void logRangedStart(Session session, ServerPlayerEntity bot, int slot,
                                       double distance, String phase) {
        TrainingLog.event("RANGE_START bot=" + session.botName
                + " item=" + itemPath(bot.getInventory().getStack(slot))
                + " slot=" + slot + " phase=" + phase
                + " distance=" + String.format(Locale.ROOT, "%.2f", distance));
    }

    private static void stopRangedCombat(MinecraftServer server, Session session, double distance) {
        HeroBotAdapter.setUsing(server, session.botName, false);
        TrainingLog.event("RANGE_STOP bot=" + session.botName
                + " distance=" + String.format(Locale.ROOT, "%.2f", distance));
        clearRangedState(session);
        restoreItemLease(server, session, HeroBotAdapter.findPlayer(server, session.botName));
    }

    private static void cancelRangedCombat(MinecraftServer server, Session session, String reason) {
        if (!session.rangedActive) {
            return;
        }
        HeroBotAdapter.setUsing(server, session.botName, false);
        TrainingLog.event("RANGE_STOP bot=" + session.botName + " reason=" + reason);
        clearRangedState(session);
        restoreItemLease(server, session, HeroBotAdapter.findPlayer(server, session.botName));
    }

    private static void clearRangedState(Session session) {
        session.rangedActive = false;
        session.rangedPhase = RangedPhase.IDLE;
        session.rangedSlot = -1;
        session.wTapNeedsRestart = false;
    }

    private static int findChargedCrossbowSlot(ServerPlayerEntity bot) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = bot.getInventory().getStack(slot);
            if (stack.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(stack)) {
                return slot;
            }
        }
        return -1;
    }

    /** Beim Rückzug eine Wind Charge werfen (Rückstoß für Abstand). */
    private static void tryWindCharge(MinecraftServer server, Session session, ServerPlayerEntity bot) {
        if (session.tick - session.lastWindChargeTick < WIND_CHARGE_COOLDOWN_TICKS) {
            return;
        }
        if (findInventorySlot(bot, stack -> stack.isOf(Items.WIND_CHARGE)) < 0) {
            return;
        }
        if (!useItemOnce(server, session, bot, Items.WIND_CHARGE)) return;
        session.lastWindChargeTick = session.tick;
        TrainingLog.event("WIND_CHARGE bot=" + session.botName
                + " offhand=" + itemPath(bot.getEquippedStack(net.minecraft.entity.EquipmentSlot.OFFHAND)));
    }

    /** Uses XP bottles only when a damaged, equipped Mending item can receive the XP orbs. */
    private static boolean tryMendingExperienceBottle(MinecraftServer server, Session session,
                                                       ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (session.itemLease != null || session.tick - session.lastExperienceBottleTick < XP_BOTTLE_COOLDOWN_TICKS
                || bot.distanceTo(target) < XP_MEND_SAFE_DISTANCE || !bot.isOnGround()) {
            return false;
        }

        var mending = bot.getEntityWorld().getRegistryManager()
                .getOptionalEntry(Enchantments.MENDING).orElse(null);
        if (mending == null) return false;

        EquipmentSlot[] equippedSlots = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        int damagedMendingItems = 0;
        for (EquipmentSlot slot : equippedSlots) {
            ItemStack stack = bot.getEquippedStack(slot);
            if (!stack.isEmpty() && stack.getDamage() > 0
                    && EnchantmentHelper.getLevel(mending, stack) > 0) {
                damagedMendingItems++;
            }
        }
        if (damagedMendingItems == 0) return false;

        int bottleSlot = findInventorySlot(bot, stack -> stack.isOf(Items.EXPERIENCE_BOTTLE));
        if (bottleSlot < 0) return false;

        HeroBotAdapter.stopMovement(server, session.botName);
        HeroBotAdapter.setSprint(server, session.botName, false);
        HeroBotAdapter.lookAtPositionNow(server, session.botName,
                new Vec3d(bot.getX() + 0.2, bot.getY() - 0.25, bot.getZ() + 0.2));
        if (!useItemOnceFromSlot(server, session, bot, bottleSlot)) return false;

        session.lastExperienceBottleTick = session.tick;
        TrainingLog.event("XP_MEND_THROW bot=" + session.botName
                + " damaged_mending_items=" + damagedMendingItems
                + " distance=" + String.format(Locale.ROOT, "%.2f", bot.distanceTo(target)));
        return true;
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

    /** Finds an item anywhere in the 36-slot player inventory, preferring the selected hotbar slot. */
    private static int findInventorySlot(ServerPlayerEntity bot, Predicate<ItemStack> matches) {
        int selected = bot.getInventory().getSelectedSlot();
        if (matches.test(bot.getInventory().getStack(selected))) return selected;
        for (int slot = 0; slot < 9; slot++) {
            if (slot != selected && matches.test(bot.getInventory().getStack(slot))) return slot;
        }
        for (int slot = 9; slot < 36; slot++) {
            if (matches.test(bot.getInventory().getStack(slot))) return slot;
        }
        return -1;
    }

    /**
     * Temporarily equips an inventory item in a hotbar slot while preserving both stacks.
     * HeroBot still performs the actual use action; this only prepares its hand.
     */
    private static ItemLease equipItemForHeroBot(MinecraftServer server, Session session,
                                                  ServerPlayerEntity bot, Predicate<ItemStack> matches) {
        int sourceSlot = findInventorySlot(bot, matches);
        return equipItemAtSlot(server, session, bot, sourceSlot);
    }

    private static ItemLease equipItemAtSlot(MinecraftServer server, Session session,
                                              ServerPlayerEntity bot, int sourceSlot) {
        if (session.itemLease != null || sourceSlot < 0 || sourceSlot >= 36) return null;
        int previousSelected = bot.getInventory().getSelectedSlot();
        int hotbarSlot = sourceSlot < 9 ? sourceSlot : previousSelected;
        boolean movedFromInventory = sourceSlot >= 9;
        ItemStack displaced = movedFromInventory
                ? bot.getInventory().getStack(hotbarSlot).copy() : ItemStack.EMPTY;
        if (movedFromInventory) {
            ItemStack wanted = bot.getInventory().getStack(sourceSlot).copy();
            bot.getInventory().setStack(sourceSlot, displaced.copy());
            bot.getInventory().setStack(hotbarSlot, wanted);
        }
        ItemLease lease = new ItemLease(sourceSlot, hotbarSlot, previousSelected,
                movedFromInventory, displaced);
        session.itemLease = lease;
        session.itemLeaseRestoreTick = Long.MAX_VALUE;
        if (bot.getInventory().getSelectedSlot() != hotbarSlot) {
            HeroBotAdapter.selectHotbar(server, session.botName, hotbarSlot);
        }
        return lease;
    }

    private static boolean useItemOnce(MinecraftServer server, Session session,
                                       ServerPlayerEntity bot, net.minecraft.item.Item item) {
        int sourceSlot = findInventorySlot(bot, stack -> stack.isOf(item));
        return useItemOnceFromSlot(server, session, bot, sourceSlot);
    }

    private static boolean useItemOnceFromSlot(MinecraftServer server, Session session,
                                                ServerPlayerEntity bot, int sourceSlot) {
        ItemLease lease = equipItemAtSlot(server, session, bot, sourceSlot);
        if (lease == null) return false;
        HeroBotAdapter.useOnce(server, session.botName);
        session.itemLeaseRestoreTick = session.tick + 1;
        return true;
    }

    private static void restoreItemLease(MinecraftServer server, Session session, ServerPlayerEntity bot) {
        ItemLease lease = session.itemLease;
        if (lease == null) return;
        if (bot != null && lease.movedFromInventory()) {
            ItemStack remainder = bot.getInventory().getStack(lease.hotbarSlot()).copy();
            bot.getInventory().setStack(lease.sourceSlot(), remainder);
            bot.getInventory().setStack(lease.hotbarSlot(), lease.displacedStack().copy());
        }
        session.itemLease = null;
        session.itemLeaseRestoreTick = NO_PENDING_CRIT;
        if (bot != null && bot.getInventory().getSelectedSlot() != lease.previousSelectedSlot()) {
            HeroBotAdapter.selectHotbar(server, session.botName, lease.previousSelectedSlot());
        }
    }

    /** Pfeile im Inventar oder in der Offhand (einschliesslich Spektral- und Effektpfeile). */
    private static boolean hasArrows(ServerPlayerEntity bot) {
        for (int slot = 0; slot < 36; slot++) {
            if (isArrow(bot.getInventory().getStack(slot))) {
                return true;
            }
        }
        return isArrow(bot.getOffHandStack());
    }

    private static boolean hasCrossbowProjectiles(ServerPlayerEntity bot) {
        return hasArrows(bot) || bot.getOffHandStack().isOf(Items.FIREWORK_ROCKET);
    }

    private static boolean isArrow(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        String path = itemPath(stack);
        return path.equals("arrow") || path.equals("spectral_arrow") || path.equals("tipped_arrow");
    }

    /**
     * Wirft eine Splash-Potion (Stärke/Schnelligkeit) unter die eigenen Füße, wenn der Bot
     * den Effekt noch nicht hat. Der Potions-Typ wird über die Komponente gelesen.
     */
    private static void trySplashBuff(MinecraftServer server, Session session, ServerPlayerEntity bot) {
        if (session.itemLease != null) {
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
        // Nach unten zielen, damit die Splash-Potion auf den eigenen Füßen platzt.
        HeroBotAdapter.lookAtPosition(server, session.botName,
                new Vec3d(bot.getX(), bot.getY() - 1.0, bot.getZ()), 1);
        if (!useItemOnceFromSlot(server, session, bot, slot)) return;
        session.lastSplashBuffTick = session.tick;
        TrainingLog.event("SPLASH_BUFF bot=" + session.botName + " potion=" + want
                + " slot=" + slot);
    }

    /**
     * Wirft eine Splash-Potion of Damage auf den Gegner, wenn der Bot die Waffe nicht gerade
     * zum Fernkampf braucht. Zielt auf die Füße des Ziels, damit die Splash-Explosion trifft.
     * Slot-Ruecktausch wie in trySplashBuff erst im Folgetick.
     */
    private static void trySplashDamage(MinecraftServer server, Session session,
                                        ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (session.itemLease != null) {
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
        // Auf die Füße des Ziels zielen: die Splash-Explosion trifft alles im Radius darum.
        HeroBotAdapter.lookAtPositionNow(server, session.botName,
                new Vec3d(target.getX(), target.getY() + 0.1, target.getZ()));
        if (!useItemOnceFromSlot(server, session, bot, slot)) return;
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
        if (session.retreating || bot.getHealth() <= RETREAT_HEALTH_THRESHOLD || !bot.canSee(target)
                || bot.distanceTo(target) < PEARL_DISTANCE
                || session.tick - session.lastPearlTick < PEARL_COOLDOWN_TICKS) {
            return;
        }

        int pearlSlot = findInventorySlot(bot, stack -> stack.isOf(Items.ENDER_PEARL));
        if (pearlSlot < 0) {
            return;
        }

        execute(server, "player " + session.botName + " look upon " + session.targetName);
        if (!useItemOnceFromSlot(server, session, bot, pearlSlot)) return;
        // Wiki: itemCd reset hebt die Abklingzeit sofort auf — der Bot kann sofort wieder werfen.
        HeroBotAdapter.resetItemCooldown(server, session.botName, "ender_pearl");
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
                || !bot.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
                || session.retreating || session.healing
                || session.pendingCritAttackTick != NO_PENDING_CRIT
                || session.itemLease != null
                || session.tick - session.lastCrystalTick < CRYSTAL_COOLDOWN_TICKS) {
            return;
        }

        int crystalSlot = findInventorySlot(bot, stack -> stack.isOf(Items.END_CRYSTAL));
        if (crystalSlot < 0) {
            return;
        }
        int obsidianSlot = findInventorySlot(bot, stack -> stack.isOf(Items.OBSIDIAN));
        BlockPos base = findCrystalBase(bot, target, obsidianSlot);
        if (base == null) {
            return;
        }

        session.crystalBase = new Vec3d(base.getX(), base.getY(), base.getZ());
        session.crystalReturnSlot = bot.getInventory().getSelectedSlot();
        session.crystalDtap = target.hurtTime > 0
                && (target.getVelocity().x * target.getVelocity().x
                + target.getVelocity().z * target.getVelocity().z > 0.0025
                || session.tick - session.lastTargetHitTick <= DECISION_INTERVAL_TICKS);
        session.lastCrystalTick = session.tick;
        session.crystalNextTick = session.tick + 1;

        // Eine neue Obsidian-Basis wird auf den festen Block unter der Position gesetzt.
        if (isCrystalBaseAt(bot, session.crystalBase)) {
            session.crystalPhase = CrystalPhase.OBIS_PLACED;
        } else {
            Vec3d supportTop = new Vec3d(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
            HeroBotAdapter.lookAtPosition(server, session.botName, supportTop, 1);
            if (!useItemOnceFromSlot(server, session, bot, obsidianSlot)) {
                session.crystalPhase = CrystalPhase.IDLE;
                return;
            }
            session.crystalPhase = CrystalPhase.OBIS_PLACED;
            session.crystalNextTick = session.tick + 2; // Platzierung muss durch sein
            TrainingLog.event("CRYSTAL_OBI_PLACE bot=" + session.botName + " confirmed=false");
        }
    }

    private static BlockPos findCrystalBase(ServerPlayerEntity bot, ServerPlayerEntity target,
                                            int obsidianSlot) {
        Vec3d targetMotion = target.hurtTime > 0 ? target.getVelocity().multiply(2.0) : Vec3d.ZERO;
        Vec3d offset = new Vec3d(target.getX() + targetMotion.x - bot.getX(), 0.0,
                target.getZ() + targetMotion.z - bot.getZ());
        double distance = offset.length();
        if (distance < 1.0E-4) {
            return null;
        }
        Vec3d toward = offset.normalize();
        Vec3d sideways = new Vec3d(-toward.z, 0.0, toward.x);
        int feetY = (int) Math.floor(bot.getY());
        double[] alongFractions = {0.65, 0.75, 0.55, 0.85};
        double[] sideOffsets = {0.0, 1.0, -1.0, 1.5, -1.5, 2.0, -2.0, 2.5, -2.5};

        for (double fraction : alongFractions) {
            double along = distance * fraction;
            for (double side : sideOffsets) {
                double x = bot.getX() + toward.x * along + sideways.x * side;
                double z = bot.getZ() + toward.z * along + sideways.z * side;
                int blockX = (int) Math.floor(x);
                int blockZ = (int) Math.floor(z);
                BlockPos[] candidates = {
                        new BlockPos(blockX, feetY - 1, blockZ),
                        new BlockPos(blockX, feetY, blockZ)
                };
                for (BlockPos base : candidates) {
                    boolean existingBase = isCrystalBaseAt(bot,
                            new Vec3d(base.getX(), base.getY(), base.getZ()));
                    if (!existingBase) {
                        if (base.getY() != feetY || obsidianSlot < 0
                                || !bot.getEntityWorld().getBlockState(base).isAir()) {
                            continue;
                        }
                        BlockPos support = base.down();
                        if (!bot.getEntityWorld().getBlockState(support)
                                .isSolidBlock(bot.getEntityWorld(), support)) {
                            continue;
                        }
                    }
                    if (!isCrystalSpaceClear(bot, target, base)) {
                        continue;
                    }
                    Vec3d crystalCenter = new Vec3d(base.getX() + 0.5,
                            base.getY() + 1.0, base.getZ() + 0.5);
                    double botDistance = new Vec3d(bot.getX(), bot.getY(), bot.getZ()).distanceTo(crystalCenter);
                    double targetDistance = new Vec3d(target.getX(), target.getY(), target.getZ()).distanceTo(crystalCenter);
                    if (botDistance > 4.5 || targetDistance > 5.5
                            || targetDistance + 0.35 >= botDistance) {
                        continue;
                    }
                    return base;
                }
            }
        }
        return null;
    }

    private static boolean isCrystalSpaceClear(ServerPlayerEntity bot, ServerPlayerEntity target,
                                               BlockPos base) {
        var world = bot.getEntityWorld();
        if (!world.getBlockState(base.up()).isAir()
                || !world.getBlockState(base.up(2)).isAir()) {
            return false;
        }
        // The End Crystal hitbox is 2x2x2; a player/entity in that space blocks placement.
        Box crystalBox = new Box(base.getX() - 0.5, base.getY() + 1.0, base.getZ() - 0.5,
                base.getX() + 1.5, base.getY() + 3.0, base.getZ() + 1.5);
        return !bot.getBoundingBox().intersects(crystalBox)
                && !target.getBoundingBox().intersects(crystalBox)
                && world.getOtherEntities(bot, crystalBox, entity -> true).isEmpty();
    }

    private static boolean isCrystalBaseAt(ServerPlayerEntity bot, Vec3d pos) {
        var state = bot.getEntityWorld().getBlockState(new BlockPos(
                (int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z)));
        return state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.BEDROCK);
    }

    private static boolean hasCrystalAt(ServerPlayerEntity bot, BlockPos base) {
        Vec3d center = new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
        Box expectedBox = new Box(center.x - 0.35, center.y - 0.35, center.z - 0.35,
                center.x + 0.35, center.y + 0.35, center.z + 0.35);
        return !bot.getEntityWorld().getOtherEntities(bot, expectedBox,
                entity -> entity.getType() == net.minecraft.entity.EntityType.END_CRYSTAL).isEmpty();
    }

    /**
     * Laeuft jeden Tick ( unabhaengig vom Decision-Intervall ), damit die Phasen
     * tatsaechlich auseinanderfallen.
     */
    private static void tickCrystalBlast(MinecraftServer server, Session session,
                                         ServerPlayerEntity bot, ServerPlayerEntity target) {
        if (session.crystalPhase == CrystalPhase.IDLE) {
            return;
        }
        if (session.crystalPhase == CrystalPhase.DONE) {
            if (session.tick >= session.crystalNextTick) {
                session.crystalPhase = CrystalPhase.IDLE;
            }
            return;
        }
        if (session.tick < session.crystalNextTick) {
            return;
        }
        Vec3d ground = session.crystalBase;

        switch (session.crystalPhase) {
            case OBIS_PLACED -> {
                if (!isCrystalBaseAt(bot, ground)) {
                    // Wurf hat nicht gegriffen (Winkel, Abstand oder Kollision) — abbrechen.
                    TrainingLog.event("CRYSTAL_OBI_FAIL bot=" + session.botName + " confirmed=false");
                    restoreCrystalSlot(server, session, bot);
                    session.crystalPhase = CrystalPhase.IDLE;
                    return;
                }
                BlockPos base = BlockPos.ofFloored(ground);
                if (!isCrystalSpaceClear(bot, target, base)) {
                    TrainingLog.event("CRYSTAL_SPACE_BLOCKED bot=" + session.botName);
                    restoreCrystalSlot(server, session, bot);
                    session.crystalPhase = CrystalPhase.IDLE;
                    return;
                }
                int crystalSlot = findInventorySlot(bot, stack -> stack.isOf(Items.END_CRYSTAL));
                if (crystalSlot < 0) {
                    restoreCrystalSlot(server, session, bot);
                    session.crystalPhase = CrystalPhase.IDLE;
                    return;
                }
                HeroBotAdapter.lookAtPosition(server, session.botName,
                        new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5), 1);
                if (!useItemOnceFromSlot(server, session, bot, crystalSlot)) {
                    restoreCrystalSlot(server, session, bot);
                    session.crystalPhase = CrystalPhase.IDLE;
                    return;
                }
                session.crystalPhase = CrystalPhase.CRYSTAL_PLACED;
                session.crystalNextTick = session.tick + 2;
                TrainingLog.event("CRYSTAL_ITEM_USE bot=" + session.botName + " confirmed=false");
            }
            case CRYSTAL_PLACED -> {
                BlockPos base = BlockPos.ofFloored(ground);
                if (!hasCrystalAt(bot, base)) {
                    TrainingLog.event("CRYSTAL_PLACE_FAIL bot=" + session.botName + " confirmed=false");
                    restoreCrystalSlot(server, session, bot);
                    session.crystalPhase = CrystalPhase.IDLE;
                    return;
                }
                TrainingLog.event("CRYSTAL_PLACE_CONFIRMED bot=" + session.botName + " confirmed=true");
                Vec3d center = new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
                double botDistance = new Vec3d(bot.getX(), bot.getY(), bot.getZ()).distanceTo(center);
                double targetDistance = new Vec3d(target.getX(), target.getY(), target.getZ()).distanceTo(center);
                if (!bot.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
                        || bot.getHealth() < CRYSTAL_MIN_HEALTH
                        || targetDistance + 0.35 >= botDistance) {
                    TrainingLog.event("CRYSTAL_DETONATE_ABORT bot=" + session.botName + " reason=self_risk");
                    HeroBotAdapter.lookAtEntityNow(server, session.botName, session.targetName);
                    HeroBotAdapter.move(server, session.botName, "backward");
                    HeroBotAdapter.setSprint(server, session.botName, true);
                    restoreCrystalSlot(server, session, bot);
                    session.crystalPhase = CrystalPhase.DONE;
                    session.crystalNextTick = session.tick + 1;
                    return;
                }
                // Detonate the verified entity, rather than swinging at an assumed location.
                HeroBotAdapter.lookAtPosition(server, session.botName, center, 1);
                HeroBotAdapter.attackOnce(server, session.botName);
                restoreCrystalSlot(server, session, bot);
                TrainingLog.event("CRYSTAL_DETONATE bot=" + session.botName + " target=" + session.targetName
                        + " distance=" + String.format(Locale.ROOT, "%.2f", bot.distanceTo(target)));
                if (session.crystalDtap) {
                    session.crystalPhase = CrystalPhase.D_TAP_WAIT_SECOND;
                    session.crystalDtapDeadline = session.tick + 24;
                    session.crystalNextTick = session.tick + 1;
                } else {
                    session.crystalPhase = CrystalPhase.DONE;
                    session.crystalNextTick = session.tick + 1;
                }
            }
            case D_TAP_WAIT_SECOND -> {
                BlockPos base = BlockPos.ofFloored(ground);
                Vec3d center = new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
                double botDistance = new Vec3d(bot.getX(), bot.getY(), bot.getZ()).distanceTo(center);
                double targetDistance = new Vec3d(target.getX(), target.getY(), target.getZ()).distanceTo(center);
                if (session.tick > session.crystalDtapDeadline || target.getHealth() <= 0.0F
                        || !bot.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
                        || bot.getHealth() < CRYSTAL_MIN_HEALTH || !isCrystalBaseAt(bot, ground)
                        || botDistance > 4.5 || targetDistance > 5.5
                        || targetDistance + 0.35 >= botDistance) {
                    session.crystalPhase = CrystalPhase.DONE;
                    session.crystalNextTick = session.tick + 1;
                    TrainingLog.event("CRYSTAL_DTAP_ABORT bot=" + session.botName + " reason=safety_or_timeout");
                    return;
                }
                if (target.hurtTime > 0) return; // wait until the first hit window closes
                if (!isCrystalSpaceClear(bot, target, base) || hasCrystalAt(bot, base)) {
                    session.crystalPhase = CrystalPhase.DONE;
                    session.crystalNextTick = session.tick + 1;
                    TrainingLog.event("CRYSTAL_DTAP_ABORT bot=" + session.botName + " reason=space_blocked");
                    return;
                }
                int crystalSlot = findInventorySlot(bot, stack -> stack.isOf(Items.END_CRYSTAL));
                if (crystalSlot < 0) {
                    session.crystalPhase = CrystalPhase.DONE;
                    session.crystalNextTick = session.tick + 1;
                    return;
                }
                HeroBotAdapter.lookAtPosition(server, session.botName, center, 1);
                if (!useItemOnceFromSlot(server, session, bot, crystalSlot)) {
                    session.crystalPhase = CrystalPhase.DONE;
                    session.crystalNextTick = session.tick + 1;
                    return;
                }
                session.crystalPhase = CrystalPhase.D_TAP_CRYSTAL_PLACED;
                session.crystalNextTick = session.tick + 2;
            }
            case D_TAP_CRYSTAL_PLACED -> {
                BlockPos base = BlockPos.ofFloored(ground);
                Vec3d center = new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
                double botDistance = new Vec3d(bot.getX(), bot.getY(), bot.getZ()).distanceTo(center);
                double targetDistance = new Vec3d(target.getX(), target.getY(), target.getZ()).distanceTo(center);
                if (!hasCrystalAt(bot, base) || !bot.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
                        || bot.getHealth() < CRYSTAL_MIN_HEALTH || targetDistance + 0.35 >= botDistance) {
                    session.crystalPhase = CrystalPhase.DONE;
                    session.crystalNextTick = session.tick + 1;
                    TrainingLog.event("CRYSTAL_DTAP_ABORT bot=" + session.botName + " reason=second_blast_risk");
                    return;
                }
                HeroBotAdapter.lookAtPosition(server, session.botName, center, 1);
                HeroBotAdapter.attackOnce(server, session.botName);
                restoreCrystalSlot(server, session, bot);
                session.crystalPhase = CrystalPhase.DONE;
                session.crystalNextTick = session.tick + 1;
                TrainingLog.event("CRYSTAL_DTAP bot=" + session.botName + " target=" + session.targetName);
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
        if (session.itemLease != null && session.itemLeaseRestoreTick != Long.MAX_VALUE
                && session.tick >= session.itemLeaseRestoreTick) {
            restoreItemLease(server, session, bot);
        }
    }

    /**
     * Zeit bis zur wirksamen Angriffsschwelle aus dem tatsaechlichen Attack-Speed-Attribut.
     * Speere verlangen fuer den Jab die volle Aufladung; andere Waffen nutzen 84,8 %.
     */
    private static int weaponCooldownTicks(ServerPlayerEntity bot) {
        double attackSpeed = bot.getAttributeValue(EntityAttributes.ATTACK_SPEED);
        if (attackSpeed <= 0.0) {
            return 20;
        }
        double fullCooldown = 20.0 / attackSpeed;
        double requiredCharge = itemPath(bot.getMainHandStack()).endsWith("_spear") ? 1.0 : 0.848;
        return Math.max(1, (int) Math.ceil(requiredCharge * fullCooldown - 0.5));
    }

    private static void tryCritMelee(MinecraftServer server, Session session,
                                     ServerPlayerEntity bot, ServerPlayerEntity target, double distance,
                                     QLearningPolicy.Action movementAction) {
        // Laufender Crit-Nachschlag wird von tickCritAttack() abgearbeitet, nicht hier.
        if (session.pendingCritAttackTick != NO_PENDING_CRIT) {
            return;
        }
        if (distance > MANUAL_ATTACK_DISTANCE || !bot.canSee(target)
                || session.tick - session.lastManualAttackTick < MANUAL_ATTACK_INTERVAL_TICKS) {
            return;
        }
        // Waffen-Cooldown-Gate: erster Schlag sofort, danach erst, wenn die Waffe wieder 84,8 % hat.
        int needed = weaponCooldownTicks(bot);
        if (session.lastManualAttackTick > 0
                && session.tick - session.lastManualAttackTick < needed) {
            return;
        }
        HeroBotAdapter.lookAtEntityNow(server, session.botName, session.targetName);
        boolean nearCritSpam = distance <= 2.5;
        boolean spear = itemPath(bot.getMainHandStack()).endsWith("_spear");
        boolean goCrit = !spear && (nearCritSpam || session.meleeSwingCount % 3 == 2);
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
        if (movementAction == QLearningPolicy.Action.HOLD) {
            execute(server, "player " + session.botName + " move forward");
        }
        HeroBotAdapter.setSprint(server, session.botName, true);
        HeroBotAdapter.attackOnce(server, session.botName);
        session.meleeSwingCount++;
        session.lastManualAttackTick = session.tick;
        session.pendingSprintReleaseTick = session.tick + 1;
        session.wTapUntilTick = session.tick + 1 + W_TAP_RELEASE_TICKS;
        session.wTapNeedsRestart = false;
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
        session.pendingSprintReleaseTick = NO_PENDING_CRIT;
        session.wTapNeedsRestart = false;
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
        cancelRangedCombat(server, session, "healing");
        if (session.totemDoubleHandLease) {
            restoreItemLease(server, session, bot);
            session.totemDoubleHandLease = false;
        }
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
        ItemLease lease = equipItemAtSlot(server, session, bot, appleSlot);
        if (lease == null) return false;
        session.itemLeaseRestoreTick = Long.MAX_VALUE;
        appleSlot = lease.hotbarSlot();
        session.healReturnSlot = lease.previousSelectedSlot();
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
        cancelRangedCombat(server, session, "eating");
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
        ItemLease lease = equipItemAtSlot(server, session, bot, foodSlot);
        if (lease == null) return false;
        session.itemLeaseRestoreTick = Long.MAX_VALUE;
        foodSlot = lease.hotbarSlot();
        session.healReturnSlot = lease.previousSelectedSlot();
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

    /** True when an axe is available anywhere in the main inventory. */
    private static boolean hasAxe(ServerPlayerEntity player) {
        for (int slot = 0; slot < 36; slot++) {
            if (itemPath(player.getInventory().getStack(slot)).endsWith("_axe")) {
                return true;
            }
        }
        return false;
    }

    private static String humanState(Session session, ServerPlayerEntity bot, ServerPlayerEntity target) {
        return session.policy.observe(bot.distanceTo(target), target.getHealth(), bot.getHealth(),
                weaponClass(target.getMainHandStack()), target.isOnGround(), bot.isOnGround(),
                isBlocking(bot), hasAxe(target), target.canSee(bot), isFacing(bot, target),
                true, target.isGliding());
    }

    private static String combatState(Session session, ServerPlayerEntity bot, ServerPlayerEntity target) {
        int cooldown = weaponCooldownTicks(bot);
        boolean attackReady = session.lastManualAttackTick <= 0
                || session.tick - session.lastManualAttackTick >= cooldown;
        return session.policy.observe(bot.distanceTo(target), bot.getHealth(), target.getHealth(),
                weaponClass(bot.getMainHandStack()), bot.isOnGround(), target.isOnGround(),
                isBlocking(target), hasAxe(bot), bot.canSee(target), isFacing(target, bot),
                attackReady, bot.isGliding());
    }

    private static String weaponClass(ItemStack stack) {
        String path = itemPath(stack);
        if (path.endsWith("_sword")) return "sword";
        if (path.endsWith("_axe")) return "axe";
        if (path.endsWith("_spear")) return "spear";
        if (path.equals("mace")) return "mace";
        if (path.equals("trident")) return "trident";
        if (path.equals("bow")) return "bow";
        if (path.equals("crossbow")) return "crossbow";
        return "other";
    }

    private static boolean isFacing(ServerPlayerEntity viewer, ServerPlayerEntity other) {
        Vec3d toward = new Vec3d(other.getX() - viewer.getX(), 0.0, other.getZ() - viewer.getZ()).normalize();
        Vec3d facing = viewer.getRotationVec(1.0F);
        facing = new Vec3d(facing.x, 0.0, facing.z).normalize();
        return toward.lengthSquared() > 0.0 && facing.dotProduct(toward) >= 0.45;
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
        restoreItemLease(server, session, bot);
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
        if (session.pathing) {
            HeroBotAdapter.stopPathing(session.server, session.botName);
            session.pathing = false;
        }
        if (session.itemLease != null) {
            restoreItemLease(session.server, session, HeroBotAdapter.findPlayer(session.server, session.botName));
        }
        HeroBotAdapter.restoreCombatSettings(session.server, session.previousCombatSettings);
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
        private final HeroBotAdapter.CombatSettingsSnapshot previousCombatSettings;
        private ItemLease itemLease;
        private long itemLeaseRestoreTick = NO_PENDING_CRIT;
        private boolean pathing;
        private final boolean nativePathSupported;
        private Vec3d lastMovementCheckPosition;
        private int stuckChecks;
        private int pathRetries;
        private long lastMovementCheckTick;
        private long obstacleEscapeUntilTick = Long.MIN_VALUE / 2;
        private boolean obstacleEscapeLeft;
        private long lastPathCommandTick = Long.MIN_VALUE / 2;
        private long tick;
        private long decisions;
        private long lastPearlTick = -PEARL_COOLDOWN_TICKS;
        private long lastExperienceBottleTick = -XP_BOTTLE_COOLDOWN_TICKS;
        private long lastHealTick = -HEAL_COOLDOWN_TICKS;
        private long lastEatTick = -EAT_COOLDOWN_TICKS;
        private boolean retreating;
        private long healingUntilTick;
        private int healReturnSlot;
        private boolean healing;
        private boolean shielding;
        private boolean totemDoubleHandLease;
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
        private long crystalDtapDeadline;
        private boolean crystalDtap;
        private Vec3d crystalBase;
        private int crystalReturnSlot = -1;
        private long pendingCritAttackTick = NO_PENDING_CRIT;
        private long lastWindChargeTick = Long.MIN_VALUE / 2;
        private long lastSplashBuffTick = Long.MIN_VALUE / 2;
        private long lastSplashDamageTick = Long.MIN_VALUE / 2;
        /** W-Tap: bis zu diesem Tick Sprint nach einem Treffer aus -> jeder Hit wird Sprint-KB-Angriff. */
        private long wTapUntilTick;
        private long pendingSprintReleaseTick = NO_PENDING_CRIT;
        private boolean wTapNeedsRestart;
        private boolean rangedActive;
        private RangedPhase rangedPhase = RangedPhase.IDLE;
        private long rangedNextTick;
        private int rangedSlot = -1;
        private int rangedRetries;
        private int meleeSwingCount;
        private String previousState;
        private QLearningPolicy.Action previousAction;
        private String humanPreviousState;
        private Vec3d humanLastPos;
        private float lastBotHealth;
        private float lastTargetHealth;

        private Session(MinecraftServer server, String botName, String targetName,
                        QLearningPolicy policy, PvPKit kit,
                        HeroBotAdapter.CombatSettingsSnapshot previousCombatSettings) {
            this.server = server;
            this.botName = botName;
            this.targetName = targetName;
            this.policy = policy;
            this.kit = kit;
            this.previousCombatSettings = previousCombatSettings;
            this.nativePathSupported = HeroBotAdapter.supportsPathing(server);
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);
            ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetName);
            this.lastBotHealth = bot == null ? 20.0F : bot.getHealth();
            this.lastTargetHealth = target == null ? 20.0F : target.getHealth();
        }
    }

    private record ItemLease(int sourceSlot, int hotbarSlot, int previousSelectedSlot,
                             boolean movedFromInventory, ItemStack displacedStack) {
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
        D_TAP_WAIT_SECOND,
        D_TAP_CRYSTAL_PLACED,
        DONE
    }

    private enum RangedPhase {
        IDLE,
        BOW_DRAWING,
        BOW_RELOAD_WAIT,
        CROSSBOW_LOADING,
        CROSSBOW_FIRE_PENDING,
        CROSSBOW_VERIFY,
        CROSSBOW_RELOAD_WAIT
    }
}
