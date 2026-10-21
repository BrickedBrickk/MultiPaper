package puregero.multipaper.mastermessagingprotocol.messages.masterbound;

import puregero.multipaper.mastermessagingprotocol.ExtendedByteBuf;

/**
 * Durable before/after inventory snapshots used to recover a hopper move if
 * the backend exits before every affected chunk file has been written.
 */
public class PersistContainerTransactionMessage extends MasterBoundMessage {
    public final String world;
    public final String transactionId;
    public final Snapshot[] snapshots;

    public PersistContainerTransactionMessage(String world, String transactionId, Snapshot[] snapshots) {
        if (snapshots.length < 2 || snapshots.length > 16) {
            throw new IllegalArgumentException("A container transaction must contain between 2 and 16 snapshots");
        }
        this.world = world;
        this.transactionId = transactionId;
        this.snapshots = snapshots;
    }

    public PersistContainerTransactionMessage(ExtendedByteBuf byteBuf) {
        world = byteBuf.readString();
        transactionId = byteBuf.readString();
        int count = byteBuf.readVarInt();
        if (count < 2 || count > 16) {
            throw new IllegalArgumentException("Invalid container transaction snapshot count " + count);
        }
        snapshots = new Snapshot[count];
        for (int i = 0; i < count; i++) {
            String path = byteBuf.readString();
            int chunkX = byteBuf.readInt();
            int chunkZ = byteBuf.readInt();
            String identity = byteBuf.readString();
            byte[] before = readSnapshotData(byteBuf);
            byte[] after = readSnapshotData(byteBuf);
            snapshots[i] = new Snapshot(path, chunkX, chunkZ, identity, before, after);
        }
    }

    @Override
    public void write(ExtendedByteBuf byteBuf) {
        byteBuf.writeString(world);
        byteBuf.writeString(transactionId);
        byteBuf.writeVarInt(snapshots.length);
        for (Snapshot snapshot : snapshots) {
            byteBuf.writeString(snapshot.path());
            byteBuf.writeInt(snapshot.chunkX);
            byteBuf.writeInt(snapshot.chunkZ);
            byteBuf.writeString(snapshot.identity());
            writeSnapshotData(byteBuf, snapshot.beforeData());
            writeSnapshotData(byteBuf, snapshot.afterData());
        }
    }

    @Override
    public void handle(MasterBoundMessageHandler handler) {
        handler.handle(this);
    }

    private static byte[] readSnapshotData(ExtendedByteBuf byteBuf) {
        int length = byteBuf.readVarInt();
        if (length <= 0 || length > 2 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid container snapshot size " + length);
        }
        byte[] data = new byte[length];
        byteBuf.readBytes(data);
        return data;
    }

    private static void writeSnapshotData(ExtendedByteBuf byteBuf, byte[] data) {
        byteBuf.writeVarInt(data.length);
        byteBuf.writeBytes(data);
    }

    public record Snapshot(String path, int chunkX, int chunkZ, String identity, byte[] beforeData, byte[] afterData) {
        public Snapshot {
            if (!"region".equals(path) && !"entities".equals(path)) {
                throw new IllegalArgumentException("Unsupported container transaction path " + path);
            }
            if (identity == null) identity = "";
        }

        public Snapshot(int chunkX, int chunkZ, byte[] beforeData, byte[] afterData) {
            this("region", chunkX, chunkZ, "", beforeData, afterData);
        }

        public Snapshot(int chunkX, int chunkZ, byte[] data) {
            this("region", chunkX, chunkZ, "", data, data);
        }

        public byte[] data() {
            return afterData;
        }
    }
}
