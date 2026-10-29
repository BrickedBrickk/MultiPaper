package rc5.chest;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WillSaveChunkLaterMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class RC5StaleChestWriter extends JavaPlugin implements CommandExecutor {
    private CompoundTag staleSnapshot;
    private String worldName;

    @Override
    public void onEnable() {
        getCommand("rc5stalechest").setExecutor(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args.length > 4
                || (!args[0].equalsIgnoreCase("readrace") && args.length != 1)) {
            sender.sendMessage("Usage: /rc5stalechest capture|gapwrite|write");
            return true;
        }

        int saveBarrierWarmupMs = 150;
        int readRaceWriteDelayMs = 300;
        int readRaceCount = 4;
        if (args[0].equalsIgnoreCase("readrace")) {
            try {
                if (args.length > 1) saveBarrierWarmupMs = boundedDelay(args[1]);
                if (args.length > 2) readRaceWriteDelayMs = boundedDelay(args[2]);
                if (args.length > 3) readRaceCount = boundedReadCount(args[3]);
            } catch (IllegalArgumentException invalidDelay) {
                sender.sendMessage("RC5_READRACE=FAILED " + invalidDelay.getMessage());
                return true;
            }
        }

        worldName = Bukkit.getWorlds().get(0).getName();
        if (args[0].equalsIgnoreCase("capture")) {
            try {
                staleSnapshot = MultiPaper.readRegionFileNBT(worldName, "region", 32, 32);
                if (staleSnapshot == null || staleSnapshot.getListOrEmpty("block_entities").isEmpty()) {
                    sender.sendMessage("RC5_STALE_CAPTURE=FAILED no chunk/block entities");
                } else {
                    sender.sendMessage("RC5_STALE_CAPTURE=OK world=" + worldName + " chunk=32,32");
                }
            } catch (IOException failure) {
                sender.sendMessage("RC5_STALE_CAPTURE=FAILED " + failure.getClass().getSimpleName());
                getLogger().severe("Could not capture the fixture's prior chunk snapshot: " + failure);
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("write")) {
            if (staleSnapshot == null) {
                sender.sendMessage("RC5_STALE_WRITE=FAILED no captured snapshot");
                return true;
            }
            try {
                MultiPaper.writeRegionFileNBT(worldName, "region", 32, 32, staleSnapshot);
                sender.sendMessage("RC5_STALE_WRITE=ACCEPTED");
            } catch (IOException expectedOrFailure) {
                sender.sendMessage("RC5_STALE_WRITE=REJECTED " + expectedOrFailure.getMessage());
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("gapwrite")) {
            if (staleSnapshot == null) {
                sender.sendMessage("RC5_GAP_STALE_WRITE=FAILED no captured snapshot");
                return true;
            }
            try {
                if (MultiPaper.getChunkHolder(worldName, 32, 32) != null) {
                    sender.sendMessage("RC5_GAP_STALE_WRITE=FAILED local holder still exists");
                    return true;
                }
                // Ensure the actual chunk unload's unlock and final unsubscribe messages
                // have been processed by Master before testing the ownerless interval.
                MultiPaper.flushMasterConnectionAsync().get(10, TimeUnit.SECONDS);
                if (MultiPaper.getChunkHolder(worldName, 32, 32) != null) {
                    sender.sendMessage("RC5_GAP_STALE_WRITE=FAILED holder reappeared during barrier");
                    return true;
                }
                MultiPaper.writeRegionFileNBT(worldName, "region", 32, 32, staleSnapshot.copy());
                sender.sendMessage("RC5_GAP_STALE_WRITE=ACCEPTED holder=null master-ping=passed");
            } catch (IOException expectedOrFailure) {
                sender.sendMessage("RC5_GAP_STALE_WRITE=REJECTED holder=null master-ping=passed " + expectedOrFailure.getMessage());
            } catch (Exception failure) {
                sender.sendMessage("RC5_GAP_STALE_WRITE=FAILED " + failure.getClass().getSimpleName());
                getLogger().severe("RC5_GAP_STALE_WRITE=FAILED " + failure);
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("readrace")) {
            if (staleSnapshot == null) {
                sender.sendMessage("RC5_READRACE=FAILED no captured snapshot");
                return true;
            }
            if (MultiPaper.getChunkHolder(worldName, 32, 32) != null) {
                sender.sendMessage("RC5_READRACE=FAILED local chunk holder still exists");
                return true;
            }

            int workerCount = Math.min(readRaceCount, 32);
            ExecutorService executor = Executors.newFixedThreadPool(workerCount);
            try {
                MultiPaper.getConnection().send(new WillSaveChunkLaterMessage(worldName, 32, 32));
                Thread.sleep(saveBarrierWarmupMs);
                CountDownLatch ready = new CountDownLatch(workerCount);
                CountDownLatch start = new CountDownLatch(1);
                List<CompletableFuture<CompoundTag>> reads = new ArrayList<>(readRaceCount);
                for (int i = 0; i < readRaceCount; i++) {
                    reads.add(CompletableFuture.supplyAsync(() -> {
                        if (ready.getCount() > 0) ready.countDown();
                        try {
                            if (!start.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("read start barrier timed out");
                            }
                            return MultiPaper.readRegionFileNBT(worldName, "region", 32, 32);
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    }, executor));
                }
                if (!ready.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("read workers did not reach start barrier");
                }
                start.countDown();
                Thread.sleep(readRaceWriteDelayMs);
                boolean staleWriteAccepted;
                try {
                    MultiPaper.writeRegionFileNBT(worldName, "region", 32, 32, staleSnapshot.copy());
                    staleWriteAccepted = true;
                } catch (IOException expectedForFormerOwner) {
                    staleWriteAccepted = false;
                }
                CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).get(90, TimeUnit.SECONDS);
                int observed = countDiamonds(reads.get(0).join());
                for (CompletableFuture<CompoundTag> read : reads) {
                    if (countDiamonds(read.join()) != observed) {
                        throw new IllegalStateException("concurrent readers disagreed on chest contents");
                    }
                }
                String status = "RC5_READRACE=PASS holder=null reads=" + reads.size() + " workers=" + workerCount + " staleWrite="
                        + (staleWriteAccepted ? "ACCEPTED" : "REJECTED") + " diamond=" + observed;
                sender.sendMessage(status);
                getLogger().info(status);
            } catch (Exception failure) {
                sender.sendMessage("RC5_READRACE=FAILED " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                getLogger().severe("RC5_READRACE=FAILED " + failure);
            } finally {
                executor.shutdownNow();
            }
            return true;
        }

        sender.sendMessage("Usage: /rc5stalechest capture|gapwrite|write");
        return true;
    }

    private static int countDiamonds(CompoundTag chunk) {
        if (chunk == null) {
            throw new IllegalStateException("Master returned no chunk NBT");
        }
        for (var blockEntity : chunk.getListOrEmpty("block_entities")) {
            CompoundTag tag = (CompoundTag) blockEntity;
            if (tag.getIntOr("x", Integer.MIN_VALUE) == 512
                    && tag.getIntOr("y", Integer.MIN_VALUE) == 80
                    && tag.getIntOr("z", Integer.MIN_VALUE) == 512) {
                var items = tag.getListOrEmpty("Items");
                if (items.isEmpty()) return 0;
                return items.getCompoundOrEmpty(0).getIntOr("count", 0);
            }
        }
        throw new IllegalStateException("single chest block entity absent from Master NBT");
    }

    private static int boundedDelay(String value) {
        try {
            int delay = Integer.parseInt(value);
            if (delay < 0 || delay > 3000) {
                throw new IllegalArgumentException("delay must be between 0 and 3000 ms");
            }
            return delay;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("delay must be an integer from 0 to 3000 ms", invalid);
        }
    }

    private static int boundedReadCount(String value) {
        try {
            int count = Integer.parseInt(value);
            if (count < 1 || count > 512) {
                throw new IllegalArgumentException("read count must be between 1 and 512");
            }
            return count;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("read count must be an integer from 1 to 512", invalid);
        }
    }
}
