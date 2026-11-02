package de.codex.herobotai;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server-to-client request to open the HeroBot control screen. */
public record OpenMenuPayload(int page) implements CustomPayload {
    public static final Id<OpenMenuPayload> ID = new Id<>(Identifier.of("herobot_ai_training", "open_menu"));
    public static final PacketCodec<RegistryByteBuf, OpenMenuPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, OpenMenuPayload::page, OpenMenuPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
