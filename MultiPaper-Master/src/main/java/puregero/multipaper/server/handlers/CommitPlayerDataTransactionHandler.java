package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.CommitPlayerDataTransactionMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;
import puregero.multipaper.server.PlayerDataOperationQueue;
import puregero.multipaper.server.PlayerDataTransactionManager;
import puregero.multipaper.server.ServerConnection;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CommitPlayerDataTransactionHandler {
    private CommitPlayerDataTransactionHandler() { }

    public static void handle(ServerConnection sender, CommitPlayerDataTransactionMessage message) {
        if (!sender.supportsPlayerDataTransactionProtocol()) {
            sender.sendReply(new BooleanMessageReply(false), message);
            return;
        }

        UUID[] playerIds = Arrays.stream(message.participants).map(CommitPlayerDataTransactionMessage.Participant::playerId).toArray(UUID[]::new);
        PlayerDataOperationQueue.submitAll(Arrays.asList(playerIds), () -> {
            boolean committed = false;
            List<ServerConnection> connections = ServerConnection.getConnections();
            Map<UUID, byte[]> snapshots = new HashMap<>();
            Map<UUID, byte[]> expectedHashes = new HashMap<>();

            // Hold the same connection-list monitor used by connect/disconnect
            // and socket-close handling from ownership validation through the
            // durable journal commit.
            synchronized (connections) {
                if (connections.contains(sender)) {
                    boolean senderOwnsEveryParticipant = true;
                    boolean allOwnersMatch = true;
                    for (CommitPlayerDataTransactionMessage.Participant participant : message.participants) {
                        ServerConnection owner = null;
                        for (ServerConnection candidate : connections) {
                            if (candidate.hasPlayer(participant.playerId())) {
                                owner = candidate;
                                break;
                            }
                        }
                        if (owner == null || owner.isPlayerDisconnecting(participant.playerId())
                                || !participant.ownerName().equals(owner.getBungeeCordName())) {
                            allOwnersMatch = false;
                            break;
                        }
                        if (owner != sender) senderOwnsEveryParticipant = false;
                        expectedHashes.put(participant.playerId(), participant.expectedPlayerDataHash());
                        snapshots.put(participant.playerId(), participant.playerData());
                    }

                    // This endpoint only accepts same-backend transactions until the
                    // remote owner has an explicit prepare/attest phase. Matching an
                    // owner name supplied by the sender is not owner consent and must
                    // not authorize replacing that owner's full playerdata image.
                    if (allOwnersMatch && senderOwnsEveryParticipant) {
                        try {
                            committed = PlayerDataTransactionManager.commitIfCurrent(
                                    message.world, message.transactionId, expectedHashes, snapshots);
                        } catch (IOException failure) {
                            System.err.println("Failed to commit playerdata transaction " + message.transactionId + " for world " + message.world);
                            failure.printStackTrace();
                        }
                    }
                }
            }

            sender.sendReply(new BooleanMessageReply(committed), message);
        });
    }
}
