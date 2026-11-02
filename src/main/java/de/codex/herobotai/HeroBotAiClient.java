package de.codex.herobotai;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.client.MinecraftClient;

public final class HeroBotAiClient implements ClientModInitializer {
    public static final String MOD_ID = "herobot_ai_training";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(OpenMenuPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                int page = payload.page() == HeroBotMenuScreen.KITS_PAGE
                        ? HeroBotMenuScreen.KITS_PAGE : HeroBotMenuScreen.HOME_PAGE;
                client.setScreen(new HeroBotMenuScreen(page, client.currentScreen));
            });
        });
        TrainingStore.load();
        DiagnosticsLog.writeEvent("Mod gestartet; HeroBot installiert=" + DiagnosticsLog.isHeroBotLoaded());
        LOGGER.info("HeroBot AI Training bereit. Trainingsdaten und Debug-Log bleiben lokal.");
    }
}
