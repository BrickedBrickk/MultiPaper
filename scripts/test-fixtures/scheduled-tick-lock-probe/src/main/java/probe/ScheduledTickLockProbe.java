package probe;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class ScheduledTickLockProbe extends JavaPlugin implements Listener {
    private final Map<String, Integer> pistonRetracts = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent event) {
        pistonRetracts.merge(key(event.getBlock().getWorld().getName(), event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ()), 1, Integer::sum);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean schedule = args.length == 6 && (args[0].equalsIgnoreCase("schedule") || args[0].equalsIgnoreCase("scheduletorch"));
        boolean inspectTick = args.length == 5 && args[0].equalsIgnoreCase("count");
        boolean inspectSpawn = args.length == 2 && args[0].equalsIgnoreCase("spawn");
        boolean inspectTopology = args.length == 4 && args[0].equalsIgnoreCase("topology");
        boolean inspectEntities = args.length == 4 && args[0].equalsIgnoreCase("entitydebug");
        boolean inspectPiston = args.length == 6 && args[0].equalsIgnoreCase("pistonwatch")
                && (args[1].equalsIgnoreCase("reset") || args[1].equalsIgnoreCase("count"));
        boolean broadcast = args.length == 5 && args[0].equalsIgnoreCase("broadcast");
        boolean sendBlock = args.length == 5 && args[0].equalsIgnoreCase("sendblock");
        boolean lockAction = args.length == 4 && (args[0].equalsIgnoreCase("lock") || args[0].equalsIgnoreCase("unlock") || args[0].equalsIgnoreCase("state"));
        if (!schedule && !inspectTick && !inspectSpawn && !inspectTopology && !inspectEntities && !inspectPiston && !broadcast && !sendBlock && !lockAction) {
            sender.sendMessage("Usage: /ticklockprobe <lock|unlock|state|entitydebug> <world> <chunkX> <chunkZ>, /ticklockprobe topology <world> <chunkX> <chunkZ>, /ticklockprobe <schedule|scheduletorch> <world> <blockX> <y> <blockZ> <delay>, /ticklockprobe count <world> <blockX> <y> <blockZ>, /ticklockprobe <broadcast|sendblock> <world> <blockX> <y> <blockZ>, /ticklockprobe pistonwatch <reset|count> <world> <x> <y> <z>, or /ticklockprobe spawn <world>");
            return true;
        }
        try {
            String worldName = inspectPiston ? args[2] : args[1];
            World world = Bukkit.getWorld(worldName);
            if (world == null) throw new IllegalStateException("World is not loaded: " + worldName);
            if (inspectSpawn) {
                org.bukkit.Location spawn = world.getSpawnLocation();
                sender.sendMessage("worldSpawn=" + spawn.getBlockX() + "," + spawn.getBlockY() + "," + spawn.getBlockZ() + ",yaw=" + spawn.getYaw() + ",pitch=" + spawn.getPitch());
                return true;
            }
            if (inspectPiston) {
                int x = Integer.parseInt(args[3]);
                int y = Integer.parseInt(args[4]);
                int z = Integer.parseInt(args[5]);
                String key = key(worldName, x, y, z);
                if (args[1].equalsIgnoreCase("reset")) pistonRetracts.put(key, 0);
                sender.sendMessage("pistonRetractCount=" + pistonRetracts.getOrDefault(key, 0));
                return true;
            }
            if (inspectTopology) {
                int chunkX = Integer.parseInt(args[2]);
                int chunkZ = Integer.parseInt(args[3]);
                Class<?> multiPaper = Class.forName("puregero.multipaper.MultiPaper");
                Object holder = multiPaper.getMethod("getChunkHolder", String.class, int.class, int.class)
                        .invoke(null, worldName, chunkX, chunkZ);
                Object owner = holder.getClass().getField("externalOwner").get(holder);
                String ownerName = owner == null ? "none" : String.valueOf(owner.getClass().getMethod("getName").invoke(owner));
                Object subscribers = holder.getClass().getField("externalSubscribers").get(holder);
                java.util.List<String> subscriberStates = new java.util.ArrayList<>();
                for (Object subscriber : (java.util.Collection<?>) subscribers) {
                    Object connection = subscriber.getClass().getMethod("getConnection").invoke(subscriber);
                    boolean open = connection != null && (boolean) connection.getClass().getMethod("isOpen").invoke(connection);
                    subscriberStates.add(subscriber.getClass().getMethod("getName").invoke(subscriber) + ":open=" + open);
                }
                sender.sendMessage("topology worldUid=" + world.getUID() + " owner=" + ownerName + " ownerIsMe="
                        + (owner != null && (boolean) owner.getClass().getMethod("isMe").invoke(owner))
                        + " currentChunk=" + (holder.getClass().getMethod("getCurrentChunk").invoke(holder) != null)
                        + " subscribers=" + subscriberStates);
                return true;
            }
            Class<?> multiPaper = Class.forName("puregero.multipaper.MultiPaper");
            if (broadcast || sendBlock) {
                int x = Integer.parseInt(args[2]);
                int y = Integer.parseInt(args[3]);
                int z = Integer.parseInt(args[4]);
                Object level = world.getClass().getMethod("getHandle").invoke(world);
                Object pos = Class.forName("net.minecraft.core.BlockPos").getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
                if (broadcast) {
                    Class<?> handler = Class.forName("puregero.multipaper.MultiPaperChunkHandler");
                    Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
                    handler.getMethod("broadcastChangesToOtherServers", serverLevel, pos.getClass()).invoke(null, level, pos);
                    sender.sendMessage("direct external block flush invoked at " + x + "," + y + "," + z);
                } else {
                    Object state = level.getClass().getMethod("getBlockState", pos.getClass()).invoke(level, pos);
                    Class<?> blockPos = Class.forName("net.minecraft.core.BlockPos");
                    Class<?> blockState = Class.forName("net.minecraft.world.level.block.state.BlockState");
                    Object packet = Class.forName("net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket")
                            .getConstructor(blockPos, blockState).newInstance(pos, state);
                    Object holder = multiPaper.getMethod("getChunkHolder", String.class, int.class, int.class)
                            .invoke(null, worldName, x >> 4, z >> 4);
                    Class<?> handler = Class.forName("puregero.multipaper.MultiPaperChunkHandler");
                    Method onBlockUpdate = Arrays.stream(handler.getMethods()).filter(method -> method.getName().equals("onBlockUpdate") && method.getParameterCount() == 2).findFirst().orElseThrow();
                    onBlockUpdate.invoke(null, holder, packet);
                    sender.sendMessage("direct external block packet queued at " + x + "," + y + "," + z);
                }
                return true;
            }
            if (inspectEntities) {
                int chunkX = Integer.parseInt(args[2]);
                int chunkZ = Integer.parseInt(args[3]);
                Object holder = multiPaper.getMethod("getChunkHolder", String.class, int.class, int.class)
                        .invoke(null, worldName, chunkX, chunkZ);
                if (holder == null) {
                    sender.sendMessage("entityChunkHolder=none");
                    return true;
                }
                boolean ready = holder.getClass().getField("multiPaperEntitiesReady").getBoolean(holder);
                Object subscribers = holder.getClass().getField("externalEntitiesSubscribers").get(holder);
                Object chunk = holder.getClass().getMethod("getCurrentChunk").invoke(holder);
                Object entities = holder.getClass().getMethod("getEntityChunk").invoke(holder);
                sender.sendMessage("entitiesReady=" + ready + " entitySubscribers=" + ((java.util.Collection<?>) subscribers).size()
                        + " currentChunk=" + (chunk != null) + " entityChunk=" + (entities != null));
                return true;
            }
            if (inspectTick) {
                int x = Integer.parseInt(args[2]);
                int y = Integer.parseInt(args[3]);
                int z = Integer.parseInt(args[4]);
                Object pos = Class.forName("net.minecraft.core.BlockPos").getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
                Object torch = Class.forName("net.minecraft.world.level.block.Blocks").getField("REDSTONE_TORCH").get(null);
                Object level = world.getClass().getMethod("getHandle").invoke(world);
                Object scheduler = level.getClass().getMethod("getBlockTicks").invoke(level);
                boolean queued = (boolean) scheduler.getClass().getMethod("hasScheduledTick", pos.getClass(), Object.class).invoke(scheduler, pos, torch);
                boolean inFlight = (boolean) scheduler.getClass().getMethod("willTickThisTick", pos.getClass(), Object.class).invoke(scheduler, pos, torch);
                Object chunk = multiPaper.getMethod("getChunkAccess", String.class, int.class, int.class)
                        .invoke(null, worldName, x >> 4, z >> 4);
                Object chunkTicks = chunk.getClass().getMethod("getBlockTicks").invoke(chunk);
                long gameTime = (long) level.getClass().getMethod("getGameTime").invoke(level);
                java.util.List<?> queuedTicks = (java.util.List<?>) chunkTicks.getClass().getMethod("pack", long.class).invoke(chunkTicks, gameTime);
                int queuedMatches = countMatchingTicks(queuedTicks, pos, torch);
                Class<?> chunkPosType = Class.forName("net.minecraft.world.level.ChunkPos");
                Object chunkPos = chunkPosType.getConstructor(int.class, int.class).newInstance(x >> 4, z >> 4);
                java.util.List<?> inFlightTicks = (java.util.List<?>) scheduler.getClass()
                        .getMethod("snapshotInFlightTicksInChunk", chunkPosType, long.class).invoke(scheduler, chunkPos, gameTime);
                int inFlightMatches = countMatchingTicks(inFlightTicks, pos, torch);
                sender.sendMessage("redstoneTorchTick queued=" + queued + " inFlight=" + inFlight
                        + " queuedMatches=" + queuedMatches + " inFlightMatches=" + inFlightMatches
                        + " pendingCount=" + (queuedMatches + inFlightMatches));
                return true;
            }
            if (schedule) {
                int x = Integer.parseInt(args[2]);
                int y = Integer.parseInt(args[3]);
                int z = Integer.parseInt(args[4]);
                int delay = Integer.parseInt(args[5]);
                Object level = world.getClass().getMethod("getHandle").invoke(world);
                Class<?> blocks = Class.forName("net.minecraft.world.level.block.Blocks");
                Object tickBlock = blocks.getField(args[0].equalsIgnoreCase("scheduletorch") ? "REDSTONE_TORCH" : "REPEATER").get(null);
                Object pos = Class.forName("net.minecraft.core.BlockPos").getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
                Object priority = Class.forName("net.minecraft.world.ticks.TickPriority").getField("NORMAL").get(null);
                long gameTime = (long) level.getClass().getMethod("getGameTime").invoke(level);
                Class<?> scheduledTick = Class.forName("net.minecraft.world.ticks.ScheduledTick");
                Object tick = scheduledTick.getConstructor(Object.class, pos.getClass(), long.class, priority.getClass(), long.class)
                        .newInstance(tickBlock, pos, gameTime + delay, priority, 0L);
                Object scheduler = level.getClass().getMethod("getBlockTicks").invoke(level);
                scheduler.getClass().getMethod("schedule", scheduledTick).invoke(scheduler, tick);
                sender.sendMessage("tick lock probe scheduled " + (args[0].equalsIgnoreCase("scheduletorch") ? "redstone torch" : "repeater") + " tick for " + worldName + " at " + x + "," + y + "," + z);
                return true;
            }
            int cx = Integer.parseInt(args[2]);
            int cz = Integer.parseInt(args[3]);
            world.getChunkAt(cx, cz);
            Method getChunkHolder = multiPaper.getMethod("getChunkHolder", String.class, int.class, int.class);
            Object holder = getChunkHolder.invoke(null, worldName, cx, cz);
            if (holder == null) throw new IllegalStateException("Chunk holder is not available.");

            if (args[0].equalsIgnoreCase("state")) {
                Object owner = holder.getClass().getField("externalOwner").get(holder);
                Object requested = holder.getClass().getField("hasExternalLockRequest").get(holder);
                String ownerName = owner == null ? "none" : String.valueOf(owner.getClass().getMethod("getName").invoke(owner));
                boolean ownerIsMe = owner != null && (boolean) owner.getClass().getMethod("isMe").invoke(owner);
                boolean isLocal = (boolean) multiPaper.getMethod("isChunkLocal", holder.getClass()).invoke(null, holder);
                int subscribers = ((java.util.Collection<?>) holder.getClass().getField("externalSubscribers").get(holder)).size();
                Object chunk = holder.getClass().getMethod("getCurrentChunk").invoke(holder);
                Class<?> tickPacketType = Class.forName("puregero.multipaper.externalserverprotocol.SendTickListPacket");
                Class<?> levelChunkType = Class.forName("net.minecraft.world.level.chunk.LevelChunk");
                Object outgoing = tickPacketType.getConstructor(levelChunkType, boolean.class).newInstance(chunk, true);
                boolean outgoingEmpty = (boolean) tickPacketType.getMethod("isEmpty").invoke(outgoing);
                Object blockTicks = chunk.getClass().getMethod("getBlockTicks").invoke(chunk);
                Object fluidTicks = chunk.getClass().getMethod("getFluidTicks").invoke(chunk);
                int blockCount = (int) blockTicks.getClass().getMethod("count").invoke(blockTicks);
                int fluidCount = (int) fluidTicks.getClass().getMethod("count").invoke(fluidTicks);
                Object level = world.getClass().getMethod("getHandle").invoke(world);
                Class<?> chunkPosType = Class.forName("net.minecraft.world.level.ChunkPos");
                Object chunkPos = chunkPosType.getConstructor(int.class, int.class).newInstance(cx, cz);
                long gameTime = (long) level.getClass().getMethod("getGameTime").invoke(level);
                Object blockScheduler = level.getClass().getMethod("getBlockTicks").invoke(level);
                Object fluidScheduler = level.getClass().getMethod("getFluidTicks").invoke(level);
                int inFlightBlocks = ((java.util.List<?>) blockScheduler.getClass()
                        .getMethod("snapshotInFlightTicksInChunk", chunkPosType, long.class)
                        .invoke(blockScheduler, chunkPos, gameTime)).size();
                int inFlightFluids = ((java.util.List<?>) fluidScheduler.getClass()
                        .getMethod("snapshotInFlightTicksInChunk", chunkPosType, long.class)
                        .invoke(fluidScheduler, chunkPos, gameTime)).size();
                Class<?> externalBlocks = Class.forName("puregero.multipaper.MultiPaperExternalBlocksHandler");
                java.lang.reflect.Field pendingField = externalBlocks.getDeclaredField("pendingOutgoing");
                pendingField.setAccessible(true);
                int pendingOutgoing = ((java.util.Map<?, ?>) pendingField.get(null)).size();
                sender.sendMessage("tick lock state owner=" + ownerName + " ownerIsMe=" + ownerIsMe + " local=" + isLocal + " subscribers=" + subscribers + " requested=" + requested + " blockTicks=" + blockCount
                        + " fluidTicks=" + fluidCount + " inFlightBlocks=" + inFlightBlocks + " inFlightFluids=" + inFlightFluids + " outgoingEmpty=" + outgoingEmpty + " outboxPending=" + pendingOutgoing);
            } else if (args[0].equalsIgnoreCase("lock")) {
                Method lock = Arrays.stream(multiPaper.getMethods())
                        .filter(method -> method.getName().equals("lockChunk") && method.getParameterCount() == 1)
                        .findFirst().orElseThrow();
                lock.invoke(null, holder);
            } else {
                Object chunk = holder.getClass().getMethod("getCurrentChunk").invoke(holder);
                Object entitySlices = holder.getClass().getMethod("getEntityChunk").invoke(holder);
                Method unlock = Arrays.stream(multiPaper.getMethods())
                        .filter(method -> method.getName().equals("unlockChunk") && method.getParameterCount() == 3)
                        .findFirst().orElseThrow();
                Object owner = holder.getClass().getField("externalOwner").get(holder);
                boolean isLocal = owner != null && (boolean) owner.getClass().getMethod("isMe").invoke(owner);
                sender.sendMessage("tick lock probe unlock local=" + isLocal);
                unlock.invoke(null, holder, chunk, entitySlices);
            }
            sender.sendMessage("tick lock probe " + args[0] + " submitted for " + worldName + " " + cx + "," + cz);
        } catch (Throwable error) {
            sender.sendMessage("tick lock probe failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            getLogger().severe("Tick lock probe failed");
            error.printStackTrace();
        }
        return true;
    }

    private static int countMatchingTicks(java.util.List<?> ticks, Object pos, Object type) throws Exception {
        int matches = 0;
        for (Object tick : ticks) {
            Object tickPos = tick.getClass().getMethod("pos").invoke(tick);
            Object tickType = tick.getClass().getMethod("type").invoke(tick);
            if (pos.equals(tickPos) && type == tickType) matches++;
        }
        return matches;
    }

    private static String key(String world, int x, int y, int z) {
        return world + ':' + x + ':' + y + ':' + z;
    }
}
