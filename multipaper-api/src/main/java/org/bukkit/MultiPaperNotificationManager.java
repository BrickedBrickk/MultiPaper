package org.bukkit;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface MultiPaperNotificationManager {

    /**
     * Listen to notifications sent by other servers.
     * 
     * @param plugin The plugin listening to these notifications
     * @param channel The notification channel to listen to
     * @param callback A handler for any data received
     */
    void on(Plugin plugin, String channel, Consumer<byte[]> callback);

    /**
     * Listen to notifications sent by other servers.
     *
     * @param plugin The plugin listening to these notifications
     * @param channel The notification channel to listen to
     * @param callback A handler for any data received
     */
    default void onString(Plugin plugin, String channel, Consumer<String> callback) {
        on(plugin, channel, bytes -> callback.accept(new String(bytes, StandardCharsets.UTF_8)));
    }

    /**
     * Listen to notifications sent by other servers.
     *
     * @param plugin The plugin listening to these notifications
     * @param channel The notification channel to listen to
     * @param callbackWithReply A handler for any data received, and a method to reply to the server on a specified channel
     */
    void on(Plugin plugin, String channel, BiConsumer<byte[], BiConsumer<String, byte[]>> callbackWithReply);

    /**
     * Listen to notifications sent by other servers.
     *
     * @param plugin The plugin listening to these notifications
     * @param channel The notification channel to listen to
     * @param callbackWithReply A handler for any data received, and a method to reply to the server on a specified channel
     */
    default void onString(Plugin plugin, String channel, BiConsumer<String, BiConsumer<String, String>> callbackWithReply) {
        on(plugin, channel, (bytes, reply) -> callbackWithReply.accept(
                new String(bytes, StandardCharsets.UTF_8),
                (replyChannel, string) -> reply.accept(replyChannel, string.getBytes(StandardCharsets.UTF_8)))
        );
    }

    /**
     * Notify all other servers.
     *
     * @param channel The notification channel to notify on
     * @param data The data to notify other servers with
     */
    void notify(String channel, byte[] data);

    /**
     * Notify all other servers.
     *
     * @param channel The notification channel to notify on
     * @param data The data to notify other servers with
     */
    default void notify(String channel, String data) {
        notify(channel, data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Notify other servers with the specified chunk loaded
     *
     * @param chunk The chunk that's loaded
     * @param channel The notification channel to notify on
     * @param data The data to notify other servers with
     */
    void notify(Chunk chunk, String channel, byte[] data);

    /**
     * Notify other servers with the specified chunk loaded
     *
     * @param chunk The chunk that's loaded
     * @param channel The notification channel to notify on
     * @param data The data to notify other servers with
     */
    default void notify(Chunk chunk, String channel, String data) {
        notify(chunk, channel, data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Notify the owning server of the specified chunk.
     * This chunk must be loaded on this server.
     * This will notify this server if this server is the owning server.
     *
     * @param chunk The loaded chunk with an owning server
     * @param channel The notification channel to notify on
     * @param data The data to notify other servers with
     */
    void notifyOwningServer(Chunk chunk, String channel, byte[] data);

    /**
     * Notify the owning server of the specified chunk.
     * This chunk must be loaded on this server.
     * This will notify this server if this server is the owning server.
     *
     * @param chunk The loaded chunk with an owning server
     * @param channel The notification channel to notify on
     * @param data The data to notify other servers with
     */
    default void notifyOwningServer(Chunk chunk, String channel, String data) {
        notifyOwningServer(chunk, channel, data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Notify the owning server of the specified player.
     * This will notify this server if this server is the owning server.
     *
     * @param player The player with an owning server
     * @param channel The notification channel to notify on
     * @param data The data to notify other servers with
     */
    void notifyOwningServer(Player player, String channel, byte[] data);

    /**
     * Notify the owning server of the specified player.
     * This will notify this server if this server is the owning server.
     *
     * @param player The player with an owning server
     * @param channel The notification channel to notify on
     * @param data The data to notify other servers with
     */
    default void notifyOwningServer(Player player, String channel, String data) {
        notifyOwningServer(player, channel, data.getBytes(StandardCharsets.UTF_8));
    }
}
