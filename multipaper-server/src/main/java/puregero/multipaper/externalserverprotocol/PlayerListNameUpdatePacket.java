package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PlayerListNameUpdatePacket extends ExternalServerPacket {

    private final UUID uuid;
    private final @org.jspecify.annotations.Nullable Component listName;

    public PlayerListNameUpdatePacket(ServerPlayer player) {
        this.uuid = player.getUUID();
        this.listName = player.listName;
    }

    public PlayerListNameUpdatePacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
        listName = in.readBoolean() ? ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.decode(in) : null;
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeBoolean(listName != null);
        if (listName != null) ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.encode(out, listName);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player != null) {
                player.listName = listName;

                for (ServerPlayer receiver : player.level().getServer().getPlayerList().getPlayers()) {
                    if (MultiPaper.isRealPlayer(receiver) && receiver.getBukkitEntity().canSee(player.getBukkitEntity())) {
                        receiver.connection.send(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, player));
                    }
                }
            }
        });
    }
}
