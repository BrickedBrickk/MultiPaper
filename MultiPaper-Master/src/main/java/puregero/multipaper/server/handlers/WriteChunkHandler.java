package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WriteChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;
import puregero.multipaper.server.ChunkLockManager;
import puregero.multipaper.server.ChunkSubscriptionManager;
import puregero.multipaper.server.EntitiesLockManager;
import puregero.multipaper.server.ServerConnection;
import puregero.multipaper.server.util.RegionFileCache;
import se.llbit.nbt.CompoundTag;
import se.llbit.nbt.ListTag;
import se.llbit.nbt.NamedTag;
import se.llbit.nbt.SpecificTag;
import se.llbit.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class WriteChunkHandler {
    public static void handle(ServerConnection connection, WriteChunkMessage message) {
        if (message.isTransientEntities) {
            handleTransientEntities(connection, message);
            return;
        }

        if (message.path.equals("region")) {
            boolean accepted = ChunkSubscriptionManager.runIfOwnerOrUnowned(connection, message.world, message.cx, message.cz,
                    () -> writeData(connection, message, message.data));
            if (!accepted) {
                // Release any read wait registered by the old owner's preannounced save.
                ChunkLockManager.writeCompleted(connection, message.world, message.cx, message.cz);
                connection.sendReply(new BooleanMessageReply(false), message);
            }
            return;
        }

        writeData(connection, message, message.data);
    }

    private static void writeData(ServerConnection connection, WriteChunkMessage message, byte[] data) {
        RegionFileCache.putChunkDeflatedDataAsync(ReadChunkHandler.getWorldDir(message.world, message.path),
                message.path.equals("region") || message.path.equals("entities") ? message.world : null,
                message.path, message.cx, message.cz, data).whenComplete((ignored, failure) -> {
            if (message.path.equals("region")) {
                ChunkLockManager.writeCompleted(connection, message.world, message.cx, message.cz);
            }
            if (message.path.equals("entities")) {
                EntitiesLockManager.writeCompleted(connection, message.world, message.cx, message.cz);
            }

            if (failure != null) {
                System.err.println("Failed to persist " + message.path + " chunk " + message.cx + "," + message.cz + " for world " + message.world);
                failure.printStackTrace();
                connection.sendReply(new BooleanMessageReply(false), message);
                return;
            }

            connection.sendReply(new BooleanMessageReply(true), message);
        });
    }

    private static void handleTransientEntities(ServerConnection connection, WriteChunkMessage message) {
        CompletableFuture.runAsync(() -> {
            try {
                // NewChunkHolder marks saves from its current ChunkEntitySlices as transient.
                // That snapshot is authoritative for this entity chunk. Merging the old disk
                // list back into it resurrects entities that have moved to another chunk.
                CompoundTag snapshot = readCompressedCompound(message.data);
                writeData(connection, message, writeCompressedCompound(deduplicate(snapshot)));
            } catch (IOException | RuntimeException failure) {
                System.err.println("Failed to persist transient entities for chunk " + message.cx + "," + message.cz + " in world " + message.world);
                failure.printStackTrace();
                EntitiesLockManager.writeCompleted(connection, message.world, message.cx, message.cz);
                connection.sendReply(new BooleanMessageReply(false), message);
            }
        });
    }

    private static CompoundTag readCompressedCompound(byte[] compressed) throws IOException {
        try (DataInputStream input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed)))) {
            if (input.readUnsignedByte() != 10) throw new IOException("Entity NBT root is not a compound");
            input.readUTF(); // preserve-format support is handled by the writer; Minecraft uses an empty root name.
            CompoundTag root = CompoundTag.read(input).asCompound();
            if (root == null) throw new IOException("Entity NBT root is not a compound");
            return root;
        }
    }

    private static byte[] writeCompressedCompound(CompoundTag compound) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            output.writeByte(10);
            output.writeUTF("");
            compound.write(output);
        }
        return bytes.toByteArray();
    }

    static CompoundTag deduplicate(CompoundTag snapshot) {
        ListTag entitiesTo = snapshot.get("Entities").asList();
        ListTag merged = new ListTag((byte) 10, new java.util.ArrayList<>());
        Set<UUID> seenUuids = new HashSet<>();
        if (entitiesTo != null) {
            for (SpecificTag tag : entitiesTo) {
                UUID uuid = entityUuid(tag);
                if (uuid == null || seenUuids.add(uuid)) {
                    merged.add(tag);
                }
            }
        }
        return withTag(snapshot, "Entities", merged);
    }

    private static UUID entityUuid(SpecificTag tag) {
        if (tag == null || tag.asCompound() == null) return null;
        Tag uuidTag = tag.asCompound().get("UUID");
        if (!(uuidTag instanceof se.llbit.nbt.IntArrayTag intArray) || intArray.value.length != 4) return null;
        int[] value = intArray.value;
        long most = ((long) value[0] << 32) | (value[1] & 0xffffffffL);
        long least = ((long) value[2] << 32) | (value[3] & 0xffffffffL);
        return new UUID(most, least);
    }

    private static CompoundTag withTag(CompoundTag original, String name, SpecificTag replacement) {
        java.util.List<NamedTag> tags = new java.util.ArrayList<>();
        boolean replaced = false;
        for (NamedTag tag : original) {
            if (tag.isNamed(name)) {
                if (!replaced) tags.add(new NamedTag(name, replacement));
                replaced = true;
            } else {
                tags.add(tag);
            }
        }
        if (!replaced) tags.add(new NamedTag(name, replacement));
        return new CompoundTag(tags);
    }
}
