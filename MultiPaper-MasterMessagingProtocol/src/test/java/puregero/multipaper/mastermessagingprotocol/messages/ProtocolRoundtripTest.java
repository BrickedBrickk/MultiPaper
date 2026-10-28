package puregero.multipaper.mastermessagingprotocol.messages;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import puregero.multipaper.mastermessagingprotocol.ChunkKey;
import puregero.multipaper.mastermessagingprotocol.ExtendedByteBuf;
import puregero.multipaper.mastermessagingprotocol.MessageDecoder;
import puregero.multipaper.mastermessagingprotocol.MessageEncoder;
import puregero.multipaper.mastermessagingprotocol.MessageLengthDecoder;
import puregero.multipaper.mastermessagingprotocol.MessageLengthEncoder;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.CallDataStorageMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.CommitPlayerDataTransactionMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.DownloadFileMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.HelloMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.MasterBoundMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.MasterBoundProtocol;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PersistContainerTransactionMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PersistContainerTransactionMessage.Snapshot;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.FilesToSyncMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerBoundMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerBoundProtocol;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.SetSecretMessage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that every message registered on the wire protocol survives an
 * encode/decode round trip with all of its fields intact. Each message is
 * constructed via its data constructor with a deterministic sample payload,
 * serialized with write(), deserialized, re-serialized, deserialized again,
 * and compared field by field.
 */
class ProtocolRoundtripTest {

    @SuppressWarnings("unchecked")
    private static <T extends Message<?>> List<Class<? extends T>> messagesOf(Protocol<T> protocol) throws Exception {
        Field field = Protocol.class.getDeclaredField("MESSAGES");
        field.setAccessible(true);
        return (List<Class<? extends T>>) field.get(protocol);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Message<?>> java.util.function.Function<ExtendedByteBuf, T> deserializerOf(Protocol<T> protocol, Class<?> messageClass) throws Exception {
        Field classes = Protocol.class.getDeclaredField("MESSAGES");
        Field deserializers = Protocol.class.getDeclaredField("MESSAGE_DESERIALIZERS");
        classes.setAccessible(true);
        deserializers.setAccessible(true);
        int index = ((List<?>) classes.get(protocol)).indexOf(messageClass);
        assertTrue(index >= 0, "Message not registered: " + messageClass);
        return (java.util.function.Function<ExtendedByteBuf, T>) ((List<?>) deserializers.get(protocol)).get(index);
    }

    @Test
    void masterBoundMessagesRoundTrip() throws Exception {
        MasterBoundProtocol protocol = new MasterBoundProtocol();
        for (Class<? extends MasterBoundMessage> messageClass : messagesOf(protocol)) {
            roundTrip(protocol, messageClass);
        }
    }

    @Test
    void appendedPlayerDataTransactionKeepsExistingMasterBoundWireIdsStable() {
        MasterBoundProtocol protocol = new MasterBoundProtocol();
        assertEquals(3, protocol.getMessageId(new DownloadFileMessage("test")));
        assertEquals(5, protocol.getMessageId(new HelloMessage("test", UUID.randomUUID())));
        assertEquals(43, protocol.getMessageId(new PersistContainerTransactionMessage(
                "world", "transaction", new PersistContainerTransactionMessage.Snapshot[]{
                new PersistContainerTransactionMessage.Snapshot(0, 0, new byte[]{1}),
                new PersistContainerTransactionMessage.Snapshot(1, 1, new byte[]{2})
        })));
        assertEquals(44, protocol.getMessageId(new CommitPlayerDataTransactionMessage(
                "world", UUID.randomUUID(), new CommitPlayerDataTransactionMessage.Participant[]{
                new CommitPlayerDataTransactionMessage.Participant(UUID.randomUUID(), "backend-a", new byte[]{1}),
                new CommitPlayerDataTransactionMessage.Participant(UUID.randomUUID(), "backend-b", new byte[]{2})
        })));
    }

    @Test
    void serverBoundMessagesRoundTrip() throws Exception {
        ServerBoundProtocol protocol = new ServerBoundProtocol();
        for (Class<? extends ServerBoundMessage> messageClass : messagesOf(protocol)) {
            roundTrip(protocol, messageClass);
        }
    }

    private <T extends Message<?>> void roundTrip(Protocol<T> protocol, Class<? extends T> messageClass) throws Exception {
        T original = constructSample(messageClass);

        ExtendedByteBuf bufA = new ExtendedByteBuf(Unpooled.buffer());
        original.write(bufA);

        java.util.function.Function<ExtendedByteBuf, T> deserializer = deserializerOf(protocol, messageClass);
        T first = deserializer.apply(bufA);
        assertNotNull(first, "Deserializer returned null for " + messageClass.getSimpleName());

        ExtendedByteBuf reencoded = new ExtendedByteBuf(Unpooled.buffer());
        first.write(reencoded);

        T second = deserializer.apply(reencoded);
        assertFieldsEqual(messageClass, first, second, messageClass.getSimpleName());

        // The message id must resolve back to the same class.
        assertEquals(protocol.getMessageId(first), protocol.getMessageId(second));
    }

    @Test
    void fullPipelineFrameRoundTrip() {
        // master -> server framing
        EmbeddedChannel masterSideEncoder = new EmbeddedChannel(
                new MessageLengthEncoder(),
                new MessageEncoder<>(new MasterBoundProtocol()));
        EmbeddedChannel serverSideDecoder = new EmbeddedChannel(
                new MessageLengthDecoder(),
                new MessageDecoder<>(new MasterBoundProtocol()));

        UUID serverId = UUID.nameUUIDFromBytes("server-a".getBytes());
        assertTrue(masterSideEncoder.writeOutbound(new HelloMessage("server-a", serverId)));
        ByteBuf framed = masterSideEncoder.readOutbound();
        assertNotNull(framed);
        assertTrue(serverSideDecoder.writeInbound(framed));
        HelloMessage decoded = serverSideDecoder.readInbound();
        assertEquals("server-a", decoded.name);
        assertEquals(serverId, decoded.serverUuid);

        // server -> master framing
        EmbeddedChannel serverSideEncoder = new EmbeddedChannel(
                new MessageLengthEncoder(),
                new MessageEncoder<>(new ServerBoundProtocol()));
        EmbeddedChannel masterSideDecoder = new EmbeddedChannel(
                new MessageLengthDecoder(),
                new MessageDecoder<>(new ServerBoundProtocol()));

        assertTrue(serverSideEncoder.writeOutbound(new BooleanMessageReply(true)));
        ByteBuf framedReply = serverSideEncoder.readOutbound();
        assertNotNull(framedReply);
        assertTrue(masterSideDecoder.writeInbound(framedReply));
        BooleanMessageReply decodedReply = masterSideDecoder.readInbound();
        assertTrue(decodedReply.result);
    }

    @Test
    void playerDataCapabilityMarkerSurvivesBothMasterBoundDirections() {
        int marker = MasterBoundProtocol.PLAYER_DATA_TRANSACTION_CAPABILITY_ID;
        UUID serverId = UUID.nameUUIDFromBytes("capability-peer".getBytes());

        EmbeddedChannel masterEncoder = new EmbeddedChannel(
                new MessageLengthEncoder(), new MessageEncoder<>(new MasterBoundProtocol()));
        EmbeddedChannel backendDecoder = new EmbeddedChannel(
                new MessageLengthDecoder(), new MessageDecoder<>(new MasterBoundProtocol()));
        HelloMessage acknowledgement = new HelloMessage("capability-peer", serverId);
        acknowledgement.setTransactionId(marker);
        assertTrue(masterEncoder.writeOutbound(acknowledgement));
        assertTrue(backendDecoder.writeInbound((Object) masterEncoder.readOutbound()));
        HelloMessage decodedAcknowledgement = backendDecoder.readInbound();
        assertEquals(marker, decodedAcknowledgement.getTransactionId());
        assertEquals(serverId, decodedAcknowledgement.serverUuid);

        EmbeddedChannel backendEncoder = new EmbeddedChannel(
                new MessageLengthEncoder(), new MessageEncoder<>(new ServerBoundProtocol()));
        EmbeddedChannel masterDecoder = new EmbeddedChannel(
                new MessageLengthDecoder(), new MessageDecoder<>(new ServerBoundProtocol()));
        SetSecretMessage challenge = new SetSecretMessage("test-secret");
        challenge.setTransactionId(marker);
        assertTrue(backendEncoder.writeOutbound(challenge));
        assertTrue(masterDecoder.writeInbound((Object) backendEncoder.readOutbound()));
        SetSecretMessage decodedChallenge = masterDecoder.readInbound();
        assertEquals(marker, decodedChallenge.getTransactionId());
        assertEquals("test-secret", decodedChallenge.secret);
    }

    /**
     * Finds the data constructor (the one that doesn't take an ExtendedByteBuf)
     * and builds an instance with a deterministic sample for each parameter.
     */
    private static <T extends Message<?>> T constructSample(Class<T> messageClass) throws Exception {
        Constructor<T> dataConstructor = null;
        for (Constructor<?> ctor : messageClass.getDeclaredConstructors()) {
            boolean takesBuf = false;
            for (Class<?> param : ctor.getParameterTypes()) {
                if (param == ExtendedByteBuf.class) {
                    takesBuf = true;
                    break;
                }
            }
            if (!takesBuf) {
                dataConstructor = (Constructor<T>) ctor;
                break;
            }
        }
        assertNotNull(dataConstructor, "No data constructor found for " + messageClass.getSimpleName());
        dataConstructor.setAccessible(true);

        Class<?>[] paramTypes = dataConstructor.getParameterTypes();
        Object[] args = new Object[paramTypes.length];
        Random random = new Random(messageClass.getName().hashCode());
        // DataStream messages take (int streamId, byte[] data, int offset, int length)
        // where offset/length must index into the data array.
        if (paramTypes.length == 4
                && paramTypes[0] == int.class
                && paramTypes[1] == byte[].class
                && paramTypes[2] == int.class
                && paramTypes[3] == int.class) {
            byte[] data = new byte[1 + random.nextInt(24)];
            random.nextBytes(data);
            args[0] = random.nextInt();
            args[1] = data;
            args[2] = 0;
            args[3] = data.length;
        } else {
            for (int i = 0; i < paramTypes.length; i++) {
                args[i] = sampleValue(paramTypes[i], random, messageClass.getSimpleName() + ".param" + i);
            }
        }
        return dataConstructor.newInstance(args);
    }

    private static Object sampleValue(Class<?> type, Random random, String context) {
        if (type == String.class) {
            return "sample-" + context.hashCode();
        } else if (type == int.class) {
            return random.nextInt();
        } else if (type == long.class) {
            return random.nextLong();
        } else if (type == float.class) {
            return 0.5f + random.nextInt(100);
        } else if (type == double.class) {
            return 0.25d + random.nextInt(100);
        } else if (type == boolean.class) {
            return random.nextBoolean();
        } else if (type == byte[].class) {
            byte[] data = new byte[1 + random.nextInt(24)];
            random.nextBytes(data);
            return data;
        } else if (type == UUID.class) {
            return new UUID(random.nextLong(), random.nextLong());
        } else if (type == String[].class) {
            return new String[]{"alpha", "beta-gamma", ""};
        } else if (type == ChunkKey.class) {
            return new ChunkKey("world", random.nextInt(1000), random.nextInt(1000));
        } else if (type == ChunkKey[].class) {
            return new ChunkKey[]{new ChunkKey("world", 1, 2), new ChunkKey("world_nether", -3, 4)};
        } else if (type == CallDataStorageMessage.Action.class) {
            return CallDataStorageMessage.Action.SET;
        } else if (type == Map.class) {
            Map<String, String> map = new HashMap<>();
            map.put("key-one", "value-one");
            map.put("key-two", "value-two");
            return map;
        } else if (type == FilesToSyncMessage.FileToSync[].class) {
            return new FilesToSyncMessage.FileToSync[]{
                    new FilesToSyncMessage.FileToSync("plugins/config.yml", 1728000000000L),
                    new FilesToSyncMessage.FileToSync("server.properties", 1L)
            };
        } else if (type == Snapshot[].class) {
            return new Snapshot[]{
                    new Snapshot(-12, 34, new byte[]{1, 2, 3}, new byte[]{4, 5}),
                    new Snapshot(56, -78, new byte[]{9}, new byte[]{10, 11, 12})
            };
        } else if (type == puregero.multipaper.mastermessagingprotocol.messages.masterbound.CommitPlayerDataTransactionMessage.Participant[].class) {
            return new puregero.multipaper.mastermessagingprotocol.messages.masterbound.CommitPlayerDataTransactionMessage.Participant[]{
                    new puregero.multipaper.mastermessagingprotocol.messages.masterbound.CommitPlayerDataTransactionMessage.Participant(UUID.randomUUID(), "owner-a", new byte[]{1, 2}),
                    new puregero.multipaper.mastermessagingprotocol.messages.masterbound.CommitPlayerDataTransactionMessage.Participant(UUID.randomUUID(), "owner-b", new byte[]{3, 4})
            };
        } else {
            fail("Unhandled sample parameter type " + type.getName() + " for " + context);
            return null;
        }
    }

    private static void assertFieldsEqual(Class<?> cls, Object a, Object b, String context) throws Exception {
        if (cls == null || cls == Object.class) {
            return;
        }

        for (Field field : cls.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            Object valueA = field.get(a);
            Object valueB = field.get(b);
            String path = context + "." + field.getName();
            assertValuesEqual(valueA, valueB, path);
        }

        assertFieldsEqual(cls.getSuperclass(), a, b, context);
    }

    private static void assertValuesEqual(Object valueA, Object valueB, String path) throws Exception {
        if (valueA instanceof byte[] arrayA && valueB instanceof byte[] arrayB) {
            assertArrayEquals(arrayA, arrayB, path);
        } else if (valueA instanceof Snapshot[] snapshotsA && valueB instanceof Snapshot[] snapshotsB) {
            assertEquals(snapshotsA.length, snapshotsB.length, path + " length");
            for (int i = 0; i < snapshotsA.length; i++) {
                assertEquals(snapshotsA[i].chunkX(), snapshotsB[i].chunkX(), path + "[" + i + "].chunkX");
                assertEquals(snapshotsA[i].chunkZ(), snapshotsB[i].chunkZ(), path + "[" + i + "].chunkZ");
                assertArrayEquals(snapshotsA[i].beforeData(), snapshotsB[i].beforeData(), path + "[" + i + "].beforeData");
                assertArrayEquals(snapshotsA[i].afterData(), snapshotsB[i].afterData(), path + "[" + i + "].afterData");
            }
        } else if (valueA instanceof puregero.multipaper.mastermessagingprotocol.messages.masterbound.CommitPlayerDataTransactionMessage.Participant[] participantsA
                && valueB instanceof puregero.multipaper.mastermessagingprotocol.messages.masterbound.CommitPlayerDataTransactionMessage.Participant[] participantsB) {
            assertEquals(participantsA.length, participantsB.length, path + " length");
            for (int i = 0; i < participantsA.length; i++) {
                assertEquals(participantsA[i].playerId(), participantsB[i].playerId(), path + "[" + i + "].playerId");
                assertEquals(participantsA[i].ownerName(), participantsB[i].ownerName(), path + "[" + i + "].ownerName");
                assertArrayEquals(participantsA[i].playerData(), participantsB[i].playerData(), path + "[" + i + "].playerData");
            }
        } else if (valueA instanceof Object[] arrayA && valueB instanceof Object[] arrayB) {
            assertEquals(arrayA.length, arrayB.length, path + " length");
            for (int i = 0; i < arrayA.length; i++) {
                if (arrayA[i] instanceof ChunkKey keyA && arrayB[i] instanceof ChunkKey keyB) {
                    assertEquals(keyA.world, keyB.world, path + "[" + i + "].world");
                    assertEquals(keyA.x, keyB.x, path + "[" + i + "].x");
                    assertEquals(keyA.z, keyB.z, path + "[" + i + "].z");
                } else if (arrayA[i] instanceof FilesToSyncMessage.FileToSync fileA && arrayB[i] instanceof FilesToSyncMessage.FileToSync fileB) {
                    assertEquals(fileA.getPath(), fileB.getPath(), path + "[" + i + "].path");
                    assertEquals(fileA.getLastModified(), fileB.getLastModified(), path + "[" + i + "].lastModified");
                } else {
                    assertEquals(arrayA[i], arrayB[i], path + "[" + i + "]");
                }
            }
        } else if (valueA instanceof Map<?, ?> mapA && valueB instanceof Map<?, ?> mapB) {
            assertEquals(mapA, mapB, path);
        } else if (valueA instanceof Float floatA && valueB instanceof Float floatB) {
            assertEquals(floatA.floatValue(), floatB.floatValue(), 0.0001f, path);
        } else if (valueA instanceof Double doubleA && valueB instanceof Double doubleB) {
            assertEquals(doubleA.doubleValue(), doubleB.doubleValue(), 0.0001d, path);
        } else if (valueA instanceof ChunkKey keyA && valueB instanceof ChunkKey keyB) {
            assertEquals(keyA.world, keyB.world, path + ".world");
            assertEquals(keyA.x, keyB.x, path + ".x");
            assertEquals(keyA.z, keyB.z, path + ".z");
        } else if (valueA != null && !isSimple(valueA) && valueB != null) {
            assertFieldsEqual(valueA.getClass(), valueA, valueB, path);
        } else {
            assertEquals(valueA, valueB, path);
        }
    }

    private static boolean isSimple(Object value) {
        return value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof UUID
                || value instanceof Enum;
    }
}
