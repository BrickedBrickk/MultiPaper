package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadUidMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.DataMessageReply;
import puregero.multipaper.server.ServerConnection;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ReadUidHandler {

    // uid.dat is the cluster-wide identity of a world. It is created here rather than by any single backend so that
    // every backend is handed the exact same value, even when they all start against an empty master at once.
    static final Object UID_LOCK = new Object();

    public static void handle(ServerConnection connection, ReadUidMessage message) {
        CompletableFuture.runAsync(() -> {
            try {
                final byte[] uid;
                synchronized (UID_LOCK) {
                    uid = readOrCreate(new File(message.world, "uid.dat"));
                }
                connection.sendReply(new DataMessageReply(uid), message);
            } catch (IOException e) {
                e.printStackTrace();
                connection.sendReply(new DataMessageReply(new byte[0]), message);
            }
        });
    }

    private static byte[] readOrCreate(File file) throws IOException {
        if (file.isFile()) {
            byte[] existing = Files.readAllBytes(file.toPath());
            if (existing.length == 16) {
                return existing;
            }
        }

        final UUID uuid = UUID.randomUUID();
        byte[] uid = ByteBuffer.allocate(16)
            .putLong(uuid.getMostSignificantBits())
            .putLong(uuid.getLeastSignificantBits())
            .array();

        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Files.write(file.toPath(), uid);
        return uid;
    }
}
