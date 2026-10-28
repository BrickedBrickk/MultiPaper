package puregero.multipaper.mastermessagingprotocol.messages.masterbound;

import puregero.multipaper.mastermessagingprotocol.ExtendedByteBuf;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Durable participant after-images. Cross-backend commits require owner attestation before this message is accepted. */
public final class CommitPlayerDataTransactionMessage extends MasterBoundMessage {
    public static final int MAX_PLAYER_DATA_BYTES = 8 * 1024 * 1024;
    public static final int PLAYER_DATA_HASH_BYTES = 32;
    private static final int PARTICIPANT_COUNT = 2;

    public final String world;
    public final UUID transactionId;
    public final Participant[] participants;

    public CommitPlayerDataTransactionMessage(String world, UUID transactionId, Participant[] participants) {
        this.world = requireText(world, 256, "world");
        this.transactionId = java.util.Objects.requireNonNull(transactionId, "transactionId");
        if (participants == null || participants.length != PARTICIPANT_COUNT) {
            throw new IllegalArgumentException("A playerdata transaction must contain exactly two participants");
        }
        Set<UUID> ids = new HashSet<>();
        this.participants = participants.clone();
        for (Participant participant : this.participants) {
            java.util.Objects.requireNonNull(participant, "participant");
            if (!ids.add(participant.playerId())) {
                throw new IllegalArgumentException("Duplicate player in playerdata transaction: " + participant.playerId());
            }
        }
    }

    public CommitPlayerDataTransactionMessage(ExtendedByteBuf input) {
        this.world = readText(input, 256, "world");
        this.transactionId = input.readUUID();
        int count = input.readVarInt();
        if (count != PARTICIPANT_COUNT) {
            throw new IllegalArgumentException("Invalid playerdata transaction participant count " + count);
        }
        this.participants = new Participant[count];
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < count; i++) {
            UUID playerId = input.readUUID();
            String ownerName = readText(input, 128, "owner name");
            byte[] expectedHash = new byte[PLAYER_DATA_HASH_BYTES];
            input.readBytes(expectedHash);
            int length = input.readVarInt();
            if (length <= 0 || length > MAX_PLAYER_DATA_BYTES || length > input.readableBytes()) {
                throw new IllegalArgumentException("Invalid playerdata transaction image size " + length);
            }
            byte[] data = new byte[length];
            input.readBytes(data);
            if (!ids.add(playerId)) {
                throw new IllegalArgumentException("Duplicate player in playerdata transaction: " + playerId);
            }
            participants[i] = new Participant(playerId, ownerName, expectedHash, data);
        }
    }

    @Override
    public void write(ExtendedByteBuf output) {
        output.writeString(world);
        output.writeUUID(transactionId);
        output.writeVarInt(participants.length);
        for (Participant participant : participants) {
            output.writeUUID(participant.playerId());
            output.writeString(participant.ownerName());
            output.writeBytes(participant.expectedPlayerDataHash());
            byte[] data = participant.playerData();
            output.writeVarInt(data.length);
            output.writeBytes(data);
        }
    }

    @Override
    public void handle(MasterBoundMessageHandler handler) {
        handler.handle(this);
    }

    private static String readText(ExtendedByteBuf input, int maxBytes, String label) {
        int length = input.readVarInt();
        if (length <= 0 || length > maxBytes || length > input.readableBytes()) {
            throw new IllegalArgumentException("Invalid " + label + " length " + length);
        }
        String text = input.toString(input.readerIndex(), length, java.nio.charset.StandardCharsets.UTF_8);
        input.readerIndex(input.readerIndex() + length);
        return requireText(text, maxBytes, label);
    }

    private static String requireText(String value, int maxBytes, String label) {
        if (value == null || value.isBlank() || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        return value;
    }

    public record Participant(UUID playerId, String ownerName, byte[] expectedPlayerDataHash, byte[] playerData) {
        public Participant {
            java.util.Objects.requireNonNull(playerId, "playerId");
            ownerName = requireText(ownerName, 128, "owner name");
            if (expectedPlayerDataHash == null || expectedPlayerDataHash.length != PLAYER_DATA_HASH_BYTES) {
                throw new IllegalArgumentException("Expected playerdata SHA-256 must be 32 bytes");
            }
            expectedPlayerDataHash = expectedPlayerDataHash.clone();
            if (playerData == null || playerData.length == 0 || playerData.length > MAX_PLAYER_DATA_BYTES) {
                throw new IllegalArgumentException("Invalid playerdata transaction image size");
            }
            playerData = playerData.clone();
        }

        @Override
        public byte[] playerData() {
            return playerData.clone();
        }

        @Override
        public byte[] expectedPlayerDataHash() {
            return expectedPlayerDataHash.clone();
        }
    }
}
