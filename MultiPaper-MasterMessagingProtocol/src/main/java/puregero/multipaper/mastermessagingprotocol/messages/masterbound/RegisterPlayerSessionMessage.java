package puregero.multipaper.mastermessagingprotocol.messages.masterbound;

import puregero.multipaper.mastermessagingprotocol.ExtendedByteBuf;

import java.util.Objects;
import java.util.UUID;

/** Registers the live login epoch for a player owned by the sending backend. */
public final class RegisterPlayerSessionMessage extends MasterBoundMessage {
    public final UUID playerId;
    public final UUID sessionId;

    public RegisterPlayerSessionMessage(UUID playerId, UUID sessionId) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    public RegisterPlayerSessionMessage(ExtendedByteBuf input) {
        this(input.readUUID(), input.readUUID());
    }

    @Override
    public void write(ExtendedByteBuf output) {
        output.writeUUID(playerId);
        output.writeUUID(sessionId);
    }

    @Override
    public void handle(MasterBoundMessageHandler handler) {
        handler.handle(this);
    }
}
