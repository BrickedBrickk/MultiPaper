package puregero.multipaper.externalserverprotocol;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.MockedStatic;
import puregero.multipaper.ExternalServer;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.ExternalPlayer;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperInventoryHandler;
import puregero.multipaper.PlayerSyncSession;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerInventoryUpdatePacketTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void preservesPlayerSessionIdentityInThePacketEncoding() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            java.util.UUID sessionId = java.util.UUID.randomUUID();
            PlayerInventoryUpdatePacket packet = new PlayerInventoryUpdatePacket(
                    java.util.UUID.randomUUID(), sessionId, "backend-a", "items", new CompoundTag()
            );
            packet.write(buffer);

            PlayerInventoryUpdatePacket decoded = new PlayerInventoryUpdatePacket(buffer);
            assertEquals(sessionId, decoded.getPlayerSyncSessionId());
            assertEquals("backend-a", decoded.getPlayerOwnerName());
        } finally {
            buffer.release();
        }
    }

    @Test
    void acceptsAnInventoryUpdateFromTheCurrentPlayerSession() {
        java.util.UUID sessionId = java.util.UUID.randomUUID();

        assertTrue(PlayerSyncSession.matches(sessionId, sessionId, "backend-a", "backend-a"));
    }

    @Test
    void rejectsAnInventoryUpdateFromAPreviousPlayerSession() {
        assertFalse(PlayerSyncSession.matches(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "backend-a", "backend-a"));
    }

    @Test
    void rejectsAnInventoryUpdateWithoutACurrentSessionIdentity() {
        assertFalse(PlayerSyncSession.matches(null, java.util.UUID.randomUUID(), "backend-a", "backend-a"));
    }

    @Test
    void rejectsAnInventoryUpdateFromAPreviousOwnerWithinTheSamePlayerSession() {
        java.util.UUID sessionId = java.util.UUID.randomUUID();

        assertFalse(PlayerSyncSession.matches(sessionId, sessionId, "backend-b", "backend-a"));
    }

    @Test
    void acceptsAllInventoryUpdatesOnlyFromTheDeclaredPlayerOwner() {
        assertTrue(PlayerInventoryUpdatePacket.isUpdateFromPlayerOwner("enderchest", "backend-a", "backend-a"));
        assertFalse(PlayerInventoryUpdatePacket.isUpdateFromPlayerOwner("enderchest", "backend-b", "backend-a"),
                "An editor backend must not publish an Ender Chest after-image as if it came from the target owner");
        assertFalse(PlayerInventoryUpdatePacket.isUpdateFromPlayerOwner("enderchest", null, "backend-a"),
                "An unidentified connection must not publish an Ender Chest update");
        assertTrue(PlayerInventoryUpdatePacket.isUpdateFromPlayerOwner("items", "backend-a", "backend-a"));
        assertFalse(PlayerInventoryUpdatePacket.isUpdateFromPlayerOwner("items", "backend-b", "backend-a"),
                "A peer must not publish a main-inventory after-image without an owner transaction");
        assertFalse(PlayerInventoryUpdatePacket.isUpdateFromPlayerOwner("items", null, "backend-a"));
    }

    @Test
    void packetHandlerRejectsMainInventoryUpdateFromNonOwnerPeer() {
        java.util.UUID playerId = java.util.UUID.randomUUID();
        java.util.UUID sessionId = java.util.UUID.randomUUID();
        ServerPlayer player = mock(ServerPlayer.class);
        PlayerList playerList = mock(PlayerList.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ExternalServerConnection connection = mock(ExternalServerConnection.class);
        connection.externalServer = new ExternalServer("backend-b", false);
        when(server.getPlayerList()).thenReturn(playerList);
        when(playerList.getPlayer(playerId)).thenReturn(player);

        try (MockedStatic<MinecraftServer> minecraft = mockStatic(MinecraftServer.class);
             MockedStatic<MultiPaper> multiPaper = mockStatic(MultiPaper.class);
             MockedStatic<PlayerSyncSession> sessions = mockStatic(PlayerSyncSession.class);
             MockedStatic<MultiPaperInventoryHandler> inventory = mockStatic(MultiPaperInventoryHandler.class)) {
            minecraft.when(MinecraftServer::getServer).thenReturn(server);
            multiPaper.when(() -> MultiPaper.runSync(any())).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(0).run();
                return null;
            });
            sessions.when(() -> PlayerSyncSession.getSessionId(player)).thenReturn(sessionId);
            sessions.when(() -> PlayerSyncSession.getOwnerName(player)).thenReturn("backend-a");
            sessions.when(() -> PlayerSyncSession.matches(sessionId, sessionId, "backend-a", "backend-a")).thenReturn(true);

            new PlayerInventoryUpdatePacket(playerId, sessionId, "backend-a", "items", enderChestUpdate(1, 2, 3)).handle(connection);

            inventory.verifyNoInteractions();
            verify(player, never()).detectEquipmentUpdates();
        }
    }

    @Test
    void packetHandlerAcceptsMainInventoryUpdateFromDeclaredOwnerPeer() {
        java.util.UUID playerId = java.util.UUID.randomUUID();
        java.util.UUID sessionId = java.util.UUID.randomUUID();
        ExternalPlayer player = mock(ExternalPlayer.class);
        PlayerList playerList = mock(PlayerList.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ExternalServerConnection connection = mock(ExternalServerConnection.class);
        connection.externalServer = new ExternalServer("backend-a", false);
        when(server.getPlayerList()).thenReturn(playerList);
        when(playerList.getPlayer(playerId)).thenReturn(player);

        try (MockedStatic<MinecraftServer> minecraft = mockStatic(MinecraftServer.class);
             MockedStatic<MultiPaper> multiPaper = mockStatic(MultiPaper.class);
             MockedStatic<PlayerSyncSession> sessions = mockStatic(PlayerSyncSession.class);
             MockedStatic<MultiPaperInventoryHandler> inventory = mockStatic(MultiPaperInventoryHandler.class)) {
            minecraft.when(MinecraftServer::getServer).thenReturn(server);
            multiPaper.when(() -> MultiPaper.runSync(any())).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(0).run();
                return null;
            });
            multiPaper.when(() -> MultiPaper.itemStackFromNBT(any(CompoundTag.class)))
                    .thenAnswer(invocation -> itemFromWire(invocation.getArgument(0)));
            sessions.when(() -> PlayerSyncSession.getSessionId(player)).thenReturn(sessionId);
            sessions.when(() -> PlayerSyncSession.getOwnerName(player)).thenReturn("backend-a");
            sessions.when(() -> PlayerSyncSession.matches(sessionId, sessionId, "backend-a", "backend-a")).thenReturn(true);

            new PlayerInventoryUpdatePacket(playerId, sessionId, "backend-a", "items", enderChestUpdate(1, 2, 3)).handle(connection);

            inventory.verify(() -> MultiPaperInventoryHandler.updateInventory(
                    same(player), eq("items"), eq(0),
                    argThat(stack -> !stack.isEmpty() && stack.getCount() == 2),
                    argThat(stack -> !stack.isEmpty() && stack.getCount() == 3)), times(1));
            verify(player).detectEquipmentUpdates();
        }
    }

    @Test
    void packetHandlerRejectsEnderChestUpdateFromNonOwnerPeer() {
        java.util.UUID playerId = java.util.UUID.randomUUID();
        java.util.UUID sessionId = java.util.UUID.randomUUID();
        ServerPlayer player = mock(ServerPlayer.class);
        PlayerList playerList = mock(PlayerList.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ExternalServerConnection connection = mock(ExternalServerConnection.class);
        connection.externalServer = new ExternalServer("backend-b", false);
        when(server.getPlayerList()).thenReturn(playerList);
        when(playerList.getPlayer(playerId)).thenReturn(player);

        try (MockedStatic<MinecraftServer> minecraft = mockStatic(MinecraftServer.class);
             MockedStatic<MultiPaper> multiPaper = mockStatic(MultiPaper.class);
             MockedStatic<PlayerSyncSession> sessions = mockStatic(PlayerSyncSession.class);
             MockedStatic<MultiPaperInventoryHandler> inventory = mockStatic(MultiPaperInventoryHandler.class)) {
            minecraft.when(MinecraftServer::getServer).thenReturn(server);
            multiPaper.when(() -> MultiPaper.runSync(any())).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(0).run();
                return null;
            });
            sessions.when(() -> PlayerSyncSession.getSessionId(player)).thenReturn(sessionId);
            sessions.when(() -> PlayerSyncSession.getOwnerName(player)).thenReturn("backend-a");
            sessions.when(() -> PlayerSyncSession.matches(sessionId, sessionId, "backend-a", "backend-a")).thenReturn(true);

            new PlayerInventoryUpdatePacket(playerId, sessionId, "backend-a", "enderchest", new CompoundTag()).handle(connection);

            verify(player, never()).detectEquipmentUpdates();
            inventory.verifyNoInteractions();
        }
    }

    @Test
    void packetHandlerAcceptsEnderChestUpdateFromDeclaredOwnerPeer() {
        java.util.UUID playerId = java.util.UUID.randomUUID();
        java.util.UUID sessionId = java.util.UUID.randomUUID();
        ExternalPlayer player = mock(ExternalPlayer.class);
        PlayerEnderChestContainer enderChest = mock(PlayerEnderChestContainer.class);
        PlayerList playerList = mock(PlayerList.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ExternalServerConnection connection = mock(ExternalServerConnection.class);
        connection.externalServer = new ExternalServer("backend-a", false);
        when(player.getEnderChestInventory()).thenReturn(enderChest);
        when(enderChest.getContainerSize()).thenReturn(27);
        when(player.acceptEnderChestSyncRevision(4)).thenReturn(true);
        when(server.getPlayerList()).thenReturn(playerList);
        when(playerList.getPlayer(playerId)).thenReturn(player);

        try (MockedStatic<MinecraftServer> minecraft = mockStatic(MinecraftServer.class);
             MockedStatic<MultiPaper> multiPaper = mockStatic(MultiPaper.class);
             MockedStatic<PlayerSyncSession> sessions = mockStatic(PlayerSyncSession.class)) {
            minecraft.when(MinecraftServer::getServer).thenReturn(server);
            multiPaper.when(() -> MultiPaper.runSync(any())).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(0).run();
                return null;
            });
            multiPaper.when(() -> MultiPaper.nbtToBytes(any(CompoundTag.class))).thenCallRealMethod();
            multiPaper.when(() -> MultiPaper.nbtFromBytes(any(byte[].class))).thenCallRealMethod();
            multiPaper.when(() -> MultiPaper.itemStackFromNBT(any(CompoundTag.class)))
                    .thenAnswer(invocation -> itemFromWire(invocation.getArgument(0)));
            sessions.when(() -> PlayerSyncSession.getSessionId(player)).thenReturn(sessionId);
            sessions.when(() -> PlayerSyncSession.getOwnerName(player)).thenReturn("backend-a");
            sessions.when(() -> PlayerSyncSession.matches(sessionId, sessionId, "backend-a", "backend-a")).thenReturn(true);

            roundTrip(new PlayerInventoryUpdatePacket(playerId, sessionId, "backend-a", "enderchest", enderChestUpdate(4, 1, 2))).handle(connection);

            verify(player).detectEquipmentUpdates();
        }
    }

    @Test
    void packetHandlerIgnoresReplayedOrOutOfOrderEnderChestRevisions() {
        java.util.UUID playerId = java.util.UUID.randomUUID();
        java.util.UUID sessionId = java.util.UUID.randomUUID();
        ExternalPlayer player = mock(ExternalPlayer.class);
        PlayerEnderChestContainer enderChest = mock(PlayerEnderChestContainer.class);
        PlayerList playerList = mock(PlayerList.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ExternalServerConnection connection = mock(ExternalServerConnection.class);
        connection.externalServer = new ExternalServer("backend-a", false);
        when(player.getEnderChestInventory()).thenReturn(enderChest);
        when(enderChest.getContainerSize()).thenReturn(27);
        when(playerList.getPlayer(playerId)).thenReturn(player);
        when(server.getPlayerList()).thenReturn(playerList);

        try (MockedStatic<MinecraftServer> minecraft = mockStatic(MinecraftServer.class);
             MockedStatic<MultiPaper> multiPaper = mockStatic(MultiPaper.class);
             MockedStatic<PlayerSyncSession> sessions = mockStatic(PlayerSyncSession.class);
             MockedStatic<MultiPaperInventoryHandler> inventory = mockStatic(MultiPaperInventoryHandler.class)) {
            minecraft.when(MinecraftServer::getServer).thenReturn(server);
            multiPaper.when(() -> MultiPaper.runSync(any())).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(0).run();
                return null;
            });
            multiPaper.when(() -> MultiPaper.nbtToBytes(any(CompoundTag.class))).thenCallRealMethod();
            multiPaper.when(() -> MultiPaper.nbtFromBytes(any(byte[].class))).thenCallRealMethod();
            multiPaper.when(() -> MultiPaper.itemStackFromNBT(any(CompoundTag.class)))
                    .thenAnswer(invocation -> itemFromWire(invocation.getArgument(0)));
            sessions.when(() -> PlayerSyncSession.getSessionId(player)).thenReturn(sessionId);
            sessions.when(() -> PlayerSyncSession.getOwnerName(player)).thenReturn("backend-a");
            sessions.when(() -> PlayerSyncSession.matches(sessionId, sessionId, "backend-a", "backend-a")).thenReturn(true);
            when(player.acceptEnderChestSyncRevision(9)).thenReturn(true);
            when(player.acceptEnderChestSyncRevision(8)).thenReturn(false);

            roundTrip(new PlayerInventoryUpdatePacket(playerId, sessionId, "backend-a", "enderchest", enderChestUpdate(9, 8, 9))).handle(connection);
            roundTrip(new PlayerInventoryUpdatePacket(playerId, sessionId, "backend-a", "enderchest", enderChestUpdate(8, 7, 8))).handle(connection);

            inventory.verify(() -> MultiPaperInventoryHandler.updateInventory(
                    same(player), eq("enderchest"), eq(0),
                    argThat(stack -> !stack.isEmpty() && stack.getCount() == 8),
                    argThat(stack -> !stack.isEmpty() && stack.getCount() == 9)), times(1));
            verify(player, times(1)).detectEquipmentUpdates();
        }
    }

    private static CompoundTag enderChestUpdate(long revision, int replacingCount, int itemCount) {
        CompoundTag root = new CompoundTag();
        net.minecraft.nbt.ListTag items = new net.minecraft.nbt.ListTag();
        CompoundTag item = wireDiamond(itemCount);
        item.putInt("Slot", 0);
        item.put("Replacing", wireDiamond(replacingCount));
        items.add(item);
        root.put("items", items);
        root.putLong("revision", revision);
        return root;
    }

    private static PlayerInventoryUpdatePacket roundTrip(PlayerInventoryUpdatePacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.write(buffer);
            return new PlayerInventoryUpdatePacket(buffer);
        } finally {
            buffer.release();
        }
    }

    private static CompoundTag wireDiamond(int count) {
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:diamond");
        item.putInt("count", count);
        return item;
    }

    private static net.minecraft.world.item.ItemStack itemFromWire(CompoundTag item) {
        int count = item.getIntOr("count", 0);
        if (count <= 0) return net.minecraft.world.item.ItemStack.EMPTY;
        net.minecraft.world.item.ItemStack stack = mock(net.minecraft.world.item.ItemStack.class);
        when(stack.isEmpty()).thenReturn(false);
        when(stack.getCount()).thenReturn(count);
        return stack;
    }
}
