package puregero.multipaper;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;

public final class PacketSerialization {
    private PacketSerialization() {
    }

    public static Packet<?> readClientbound(byte[] bytes) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), MinecraftServer.getServer().registryAccess());
        var protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(MinecraftServer.getServer().registryAccess()));
        return protocol.codec().decode(buffer);
    }

    public static Packet<?> readServerbound(byte[] bytes) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), MinecraftServer.getServer().registryAccess());
        var protocol = GameProtocols.SERVERBOUND_TEMPLATE.bind(
            RegistryFriendlyByteBuf.decorator(MinecraftServer.getServer().registryAccess()),
            new GameProtocols.Context() {
                @Override public boolean hasInfiniteMaterials() { return false; }
                @Override public boolean canUseCommandBlocks() { return false; }
            }
        );
        return protocol.codec().decode(buffer);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static byte[] writeClientbound(Packet<?> packet) {
        ByteBuf raw = Unpooled.buffer();
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(raw, MinecraftServer.getServer().registryAccess());
        var protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(MinecraftServer.getServer().registryAccess()));
        protocol.codec().encode(buffer, (Packet) packet);
        byte[] result = new byte[raw.readableBytes()];
        raw.readBytes(result);
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static byte[] writeServerbound(Packet<?> packet) {
        ByteBuf raw = Unpooled.buffer();
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(raw, MinecraftServer.getServer().registryAccess());
        var protocol = GameProtocols.SERVERBOUND_TEMPLATE.bind(
            RegistryFriendlyByteBuf.decorator(MinecraftServer.getServer().registryAccess()),
            new GameProtocols.Context() {
                @Override public boolean hasInfiniteMaterials() { return false; }
                @Override public boolean canUseCommandBlocks() { return false; }
            }
        );
        protocol.codec().encode(buffer, (Packet) packet);
        byte[] result = new byte[raw.readableBytes()];
        raw.readBytes(result);
        return result;
    }
}
