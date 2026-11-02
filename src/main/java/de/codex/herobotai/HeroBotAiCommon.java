package de.codex.herobotai;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HeroBotAiCommon implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("herobot_ai_training");

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(OpenMenuPayload.ID, OpenMenuPayload.CODEC);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(HeroBotCommands.create("hba"));
            dispatcher.register(HeroBotCommands.create("herobotai"));
        });
        ServerTickEvents.END_SERVER_TICK.register(TrainingController::tick);
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) ->
                TrainingController.afterDeath(entity));
        ServerLifecycleEvents.SERVER_STOPPING.register(TrainingController::shutdown);
        LOGGER.info("HeroBot AI Training server logic loaded. Use /hba help for commands.");
    }
}
