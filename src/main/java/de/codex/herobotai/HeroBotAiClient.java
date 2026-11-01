package de.codex.herobotai;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HeroBotAiClient implements ClientModInitializer {
    public static final String MOD_ID = "herobot_ai_training";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        TrainingStore.load();
        DiagnosticsLog.writeEvent("Mod gestartet; HeroBot installiert=" + DiagnosticsLog.isHeroBotLoaded());
        LOGGER.info("HeroBot AI Training bereit. Trainingsdaten und Debug-Log bleiben lokal.");
    }
}
