package puregero.multipaper.server;

import puregero.multipaper.mastermessagingprotocol.ChunkKey;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.AddChunkSubscriberMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ChunkSubscribersSyncMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.RemoveChunkSubscriberMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.SetChunkOwnerMessage;
import puregero.multipaper.server.util.ChunkLock;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkSubscriptionManager {

    private static final Queue<List<ServerConnection>> objectPool = new LinkedList<>();

    // Index 0 in the list is the current chunk owning it
    private static final Map<ChunkKey, List<ServerConnection>> chunkLocks = new ConcurrentHashMap<>();
    private static final Map<ChunkKey, List<ServerConnection>> chunkSubscribers = new ConcurrentHashMap<>();
    // Remember chunks that have had an owner. Even with no subscribers, a delayed
    // write from a connected former owner must not be mistaken for first-write bootstrap.
    private static final Map<ChunkKey, ServerConnection> previousOwners = new ConcurrentHashMap<>();

    // Different server connections process messages on different Netty event loops, so
    // ownership and subscription bookkeeping must support concurrent per-connection updates.
    private static final Map<ServerConnection, Set<ChunkKey>> lockedChunks = new ConcurrentHashMap<>();
    private static final Map<ServerConnection, Set<ChunkKey>> subscribedChunks = new ConcurrentHashMap<>();

    public static ServerConnection getOwner(String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkLocks.get(key);
            if (serverConnections != null && !serverConnections.isEmpty()) {
                return serverConnections.get(0);
            }
        }
        return null;
    }

    /**
     * Runs the storage enqueue while ownership cannot change. First-write bootstrap is
     * allowed only for chunks that have never had an owner; a previously owned chunk
     * must be claimed again before any backend can replace its Master snapshot.
     */
    public static boolean runIfOwnerOrUnowned(ServerConnection connection, String world, int cx, int cz, Runnable action) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkLocks.get(key);
            boolean hasOwner = serverConnections != null && !serverConnections.isEmpty();
            if (hasOwner && serverConnections.get(0) != connection) {
                return false;
            }
            if (!hasOwner && previousOwners.containsKey(key)) {
                return false;
            }
            action.run();
            return true;
        }
    }

    public static ServerConnection getOwnerOrSubscriber(String world, int cx, int cz) {
        ServerConnection owner = getOwner(world, cx, cz);
        if (owner != null) {
            return owner;
        }

        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkSubscribers.get(key);
            if (serverConnections != null && !serverConnections.isEmpty()) {
                return serverConnections.get(0);
            }
        }

        return null;
    }

    public static ServerConnection lock(ServerConnection serverConnection, String world, int cx, int cz) {
        return lock(serverConnection, world, cx, cz, false);
    }

    public static ServerConnection lock(ServerConnection serverConnection, String world, int cx, int cz, boolean force) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkLocks.computeIfAbsent(key, key2 -> {
                synchronized (objectPool) {
                    List<ServerConnection> list = objectPool.poll();
                    if (list != null) {
                        return list;
                    }
                }
                return new ArrayList<>();
            });

            if (force && serverConnections.indexOf(serverConnection) != 0) {
                serverConnections.remove(serverConnection);
            }

            if (!serverConnections.contains(serverConnection)) {
                if (force) {
                    serverConnections.add(0, serverConnection);
                } else {
                    serverConnections.add(serverConnection);
                }

                lockedChunks.computeIfAbsent(serverConnection, k -> ConcurrentHashMap.newKeySet()).add(key);


                if (serverConnections.size() == 1 || force) {
                    if (chunkSubscribers.get(key) != null) {
                        updateOwner(serverConnections.get(0), chunkSubscribers.get(key), key.world, key.x, key.z);
                    }
                }
            }

            // A queued/non-forced locker does not become the owner. Track the connection
            // that will be responsible for the current authoritative chunk image.
            previousOwners.put(key, serverConnections.get(0));

            return serverConnections.get(0);
        }
    }

    /**
     * Atomically force-claims a requested set only when none of its chunks is
     * actively owned by another backend. Forced ownership is not a save handoff:
     * the current owner may still have newer chunk NBT that has not reached Master.
     */
    public static boolean forceLockIfNoOtherOwner(ServerConnection connection, ChunkKey[] requested) {
        int[] lockIndexes = Arrays.stream(requested)
                .mapToInt(ChunkLock::getChunkLockIndex)
                .distinct()
                .sorted()
                .toArray();
        return withChunkLocks(lockIndexes, 0, () -> {
            for (ChunkKey key : requested) {
                // Save announcements and ownership changes share this chunk lock.
                // Recheck the write barrier here, after the caller's asynchronous
                // wait, so a late announcement cannot slip between the empty check
                // and promotion of an owner that would cause its final write to be
                // rejected as a stale former-owner snapshot.
                if (ChunkLockManager.hasPendingWrites(key.world, key.x, key.z)) {
                    return false;
                }
                List<ServerConnection> owners = chunkLocks.get(key);
                if (owners != null && !owners.isEmpty() && owners.get(0) != connection) {
                    return false;
                }
            }
            for (ChunkKey key : requested) {
                lock(connection, key.world, key.x, key.z, true);
            }
            return true;
        });
    }

    private static boolean withChunkLocks(int[] indexes, int index, java.util.function.BooleanSupplier action) {
        if (index == indexes.length) {
            return action.getAsBoolean();
        }
        synchronized (ChunkLock.getChunkLock(indexes[index])) {
            return withChunkLocks(indexes, index + 1, action);
        }
    }

    public static void unlock(ServerConnection serverConnection, String world, int cx, int cz) {
        unlock(serverConnection, new ChunkKey(world, cx, cz));
    }

    public static void unlock(ServerConnection serverConnection, ChunkKey key) {
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkLocks.get(key);
            if (serverConnections != null) {
                for (int i = 0; i < serverConnections.size(); i++) {
                    if (serverConnections.get(i) == serverConnection) {
                        serverConnections.remove(i--);

                        if (i == -1) {
                            if (chunkSubscribers.get(key) != null) {
                                if (serverConnections.isEmpty()) {
                                    updateOwner(null, chunkSubscribers.get(key), key.world, key.x, key.z);
                                } else {
                                    updateOwner(serverConnections.get(0), chunkSubscribers.get(key), key.world, key.x, key.z);
                                }
                            }
                        }
                    }
                }
                if (serverConnections.isEmpty()) {
                    chunkLocks.remove(key);
                    synchronized (objectPool) {
                        objectPool.add(serverConnections);
                    }
                } else {
                    // Ownership may have moved to a queued backend; retain its identity
                    // if the current owner disconnects before the next unlock.
                    previousOwners.put(key, serverConnections.get(0));
                }
            }

            Set<ChunkKey> chunks = lockedChunks.get(serverConnection);
            if (chunks != null) {
                chunks.remove(key);
            }
        }
    }

    private static void updateOwner(ServerConnection ownerConnection, List<ServerConnection> serverConnections, String world, int cx, int cz) {
        String owner = ownerConnection == null ? "" : ownerConnection.getBungeeCordName();
        for (ServerConnection connection : serverConnections) {
            connection.send(new SetChunkOwnerMessage(world, cx, cz, owner));
        }
    }


    public static void subscribe(ServerConnection serverConnection, String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkSubscribers.computeIfAbsent(key, key2 -> {
                synchronized (objectPool) {
                    List<ServerConnection> list = objectPool.poll();
                    if (list != null) {
                        return list;
                    }
                }
                return new ArrayList<>();
            });

            if (!serverConnections.isEmpty()) {
                List<ServerConnection> singletonList = Collections.singletonList(serverConnection);
                for (ServerConnection subscriber : serverConnections) {
                    if (subscriber != serverConnection) {
                        updateSubscriberAdd(singletonList, subscriber, world, cx, cz);
                    }
                }
            }

            if (!serverConnections.contains(serverConnection)) {
                updateSubscriberAdd(serverConnections, serverConnection, world, cx, cz);
                serverConnections.add(serverConnection);
                subscribedChunks.computeIfAbsent(serverConnection, k -> ConcurrentHashMap.newKeySet()).add(key);
            }

            if (chunkLocks.get(key) != null && !chunkLocks.get(key).isEmpty()) {
                updateOwner(chunkLocks.get(key).get(0), Collections.singletonList(serverConnection), key.world, key.x, key.z);
            }
        }
    }

    public static void unsubscribe(ServerConnection serverConnection, String world, int cx, int cz) {
        unsubscribe(serverConnection, new ChunkKey(world, cx, cz));
    }

    public static void unsubscribe(ServerConnection serverConnection, ChunkKey key) {
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> serverConnections = chunkSubscribers.get(key);
            if (serverConnections != null) {
                if (serverConnections.remove(serverConnection)) {
                    updateSubscriberRemoved(serverConnections, serverConnection, key.world, key.x, key.z);
                }
                if (serverConnections.isEmpty()) {
                    chunkSubscribers.remove(key);
                    synchronized (objectPool) {
                        objectPool.add(serverConnections);
                    }
                }
            }

            Set<ChunkKey> chunks = subscribedChunks.get(serverConnection);
            if (chunks != null) {
                chunks.remove(key);
            }
        }
    }

    private static void updateSubscriberAdd(List<ServerConnection> serverConnections, ServerConnection serverConnection, String world, int cx, int cz) {
        String subscriber = serverConnection.getBungeeCordName();
        for (ServerConnection connection : serverConnections) {
            if (connection != serverConnection) {
                connection.send(new AddChunkSubscriberMessage(world, cx, cz, subscriber));
            }
        }
    }

    private static void updateSubscriberRemoved(List<ServerConnection> serverConnections, ServerConnection serverConnection, String world, int cx, int cz) {
        String unsubscriber = serverConnection.getBungeeCordName();
        for (ServerConnection connection : serverConnections) {
            connection.send(new RemoveChunkSubscriberMessage(world, cx, cz, unsubscriber));
        }
    }

    public static void syncSubscribers(ServerConnection serverConnection, String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            if (!chunkSubscribers.containsKey(key) || !chunkSubscribers.get(key).contains(serverConnection)) {
                subscribe(serverConnection, world, cx, cz);
            }

            String[] subscribers = chunkSubscribers.get(key).stream().filter(subscriber -> subscriber != serverConnection).map(ServerConnection::getBungeeCordName).toArray(String[]::new);

            serverConnection.send(new ChunkSubscribersSyncMessage(world, cx, cz, chunkLocks.get(key) != null && !chunkLocks.get(key).isEmpty() ? chunkLocks.get(key).get(0).getBungeeCordName() : "", subscribers));
        }
    }

    public static void unsubscribeAndUnlockAll(ServerConnection serverConnection) {
        Set<ChunkKey> lockedChunks = ChunkSubscriptionManager.lockedChunks.remove(serverConnection);
        if (lockedChunks != null) {
            lockedChunks.forEach(chunk -> unlock(serverConnection, chunk));
        }
        Set<ChunkKey> subscribedChunks = ChunkSubscriptionManager.subscribedChunks.remove(serverConnection);
        if (subscribedChunks != null) {
            subscribedChunks.forEach(chunk -> unsubscribe(serverConnection, chunk));
        }

        // A disconnected backend cannot send any more delayed snapshots. Retire only
        // tombstones still attributed to it; a newer owner may already have replaced one.
        for (Map.Entry<ChunkKey, ServerConnection> entry : previousOwners.entrySet()) {
            ChunkKey key = entry.getKey();
            synchronized (ChunkLock.getChunkLock(key)) {
                previousOwners.remove(key, serverConnection);
            }
        }
    }

    public static List<ServerConnection> getSubscribers(String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            List<ServerConnection> subscribers = chunkSubscribers.get(key);
            return subscribers == null ? Collections.emptyList() : List.copyOf(subscribers);
        }
    }
}
