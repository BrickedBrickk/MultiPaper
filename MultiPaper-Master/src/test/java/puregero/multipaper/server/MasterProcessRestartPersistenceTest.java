package puregero.multipaper.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadLevelMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadPlayerMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WriteChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WriteLevelMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WritePlayerMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.DataMessageReply;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerBoundMessage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasterProcessRestartPersistenceTest {

    private static final String WORLD = "process-restart-world";
    private static final String PLAYER_UUID = "c51c8d95-c2e8-4e7a-a0b6-03a2f6a44ec9";
    private static final int CHUNK_X = -33;
    private static final int CHUNK_Z = 48;
    private static final byte[] CHUNK_DATA = "persisted-chunk-data".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LEVEL_DATA = "persisted-level-data".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PLAYER_DATA = "persisted-player-data".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(60)
    void worldAndPlayerDataSurviveGracefulShutdownAndMasterJarRestart() throws Exception {
        int masterPort = findFreePort();
        int proxyPort = findFreePort();
        assertTrue(masterPort != proxyPort, "Master and proxy need distinct ports");
        Path masterDirectory = tempDir.resolve("master");
        masterDirectory.toFile().mkdirs();

        Process firstMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient firstClient = null;
        try {
            awaitListening(firstMaster, masterPort);
            firstClient = TestServerClient.connect(masterPort, "restart-writer");
            writePersistentData(firstClient);
            firstClient.close();
            firstClient = null;

            requestGracefulExit(firstMaster);
            assertTrue(firstMaster.waitFor(15, TimeUnit.SECONDS), "First Master did not stop after exit");
            assertEquals(0, firstMaster.exitValue(), "First Master process should exit successfully");
        } finally {
            closeClient(firstClient);
            stopIfRunning(firstMaster);
        }

        Process secondMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient secondClient = null;
        try {
            awaitListening(secondMaster, masterPort);
            secondClient = TestServerClient.connect(masterPort, "restart-reader");
            assertPersistentData(secondClient, "Restarted Master");

            secondClient.close();
            secondClient = null;
            requestGracefulExit(secondMaster);
            assertTrue(secondMaster.waitFor(15, TimeUnit.SECONDS), "Second Master did not stop after exit");
            assertEquals(0, secondMaster.exitValue(), "Second Master process should exit successfully");
        } finally {
            closeClient(secondClient);
            stopIfRunning(secondMaster);
        }
    }

    @Test
    @Timeout(60)
    void worldAndPlayerDataSurviveUnexpectedMasterTerminationAfterWriteAcknowledgement() throws Exception {
        int masterPort = findFreePort();
        int proxyPort = findFreePort();
        assertTrue(masterPort != proxyPort, "Master and proxy need distinct ports");
        Path masterDirectory = tempDir.resolve("crashed-master");
        masterDirectory.toFile().mkdirs();

        Process firstMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient firstClient = null;
        try {
            awaitListening(firstMaster, masterPort);
            firstClient = TestServerClient.connect(masterPort, "crash-writer");
            writePersistentData(firstClient);
            firstClient.close();
            firstClient = null;

            firstMaster.destroyForcibly();
            assertTrue(firstMaster.waitFor(10, TimeUnit.SECONDS), "Master process did not terminate forcibly");
        } finally {
            closeClient(firstClient);
            stopIfRunning(firstMaster);
        }

        Process recoveredMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient reader = null;
        try {
            awaitListening(recoveredMaster, masterPort);
            reader = TestServerClient.connect(masterPort, "crash-reader");
            assertPersistentData(reader, "Recovered Master");

            reader.close();
            reader = null;
            requestGracefulExit(recoveredMaster);
            assertTrue(recoveredMaster.waitFor(15, TimeUnit.SECONDS), "Recovered Master did not stop after exit");
            assertEquals(0, recoveredMaster.exitValue(), "Recovered Master process should exit successfully");
        } finally {
            closeClient(reader);
            stopIfRunning(recoveredMaster);
        }
    }

    private static void writePersistentData(TestServerClient client) throws Exception {
        assertAcknowledged(client.request(new WriteChunkMessage(WORLD, "region", CHUNK_X, CHUNK_Z, CHUNK_DATA)), "chunk data");
        assertAcknowledged(client.request(new WriteLevelMessage(WORLD, LEVEL_DATA)), "level data");
        assertAcknowledged(client.request(new WritePlayerMessage(WORLD, PLAYER_UUID, PLAYER_DATA)), "player data");
    }

    private static void assertPersistentData(TestServerClient client, String masterDescription) throws Exception {
        assertArrayEquals(CHUNK_DATA, readData(client.request(new ReadChunkMessage(WORLD, "region", CHUNK_X, CHUNK_Z)), masterDescription, "chunk data"));
        assertArrayEquals(LEVEL_DATA, readData(client.request(new ReadLevelMessage(WORLD)), masterDescription, "level data"));
        assertArrayEquals(PLAYER_DATA, readData(client.request(new ReadPlayerMessage(WORLD, PLAYER_UUID)), masterDescription, "player data"));
    }

    private static void assertAcknowledged(ServerBoundMessage reply, String dataType) {
        assertTrue(reply instanceof BooleanMessageReply && ((BooleanMessageReply) reply).result,
                "Master must acknowledge the " + dataType + " write before termination");
    }

    private static byte[] readData(ServerBoundMessage reply, String masterDescription, String dataType) {
        assertTrue(reply instanceof DataMessageReply, masterDescription + " should return stored " + dataType);
        return ((DataMessageReply) reply).data;
    }

    private static Process startMaster(Path workingDirectory, int masterPort, int proxyPort) throws IOException {
        Path jar = Path.of(System.getProperty("user.dir"), "..", "libs", "multipaper-master-3.0.0.jar").normalize().toAbsolutePath();
        assertTrue(jar.toFile().isFile(), "Master JAR not found: " + jar);
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        return new ProcessBuilder(java, "-Dlogging.enabled=false", "-jar", jar.toString(), "127.0.0.1:" + masterPort, Integer.toString(proxyPort))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private static void awaitListening(Process process, int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("Master process exited before listening with code " + process.exitValue());
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException ignored) {
                Thread.sleep(50);
            }
        }
        throw new IOException("Timed out waiting for Master port " + port);
    }

    private static void requestGracefulExit(Process process) throws IOException {
        process.getOutputStream().write("exit\n".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
    }

    private static void closeClient(TestServerClient client) throws InterruptedException {
        if (client != null) {
            client.close();
        }
    }

    private static void stopIfRunning(Process process) throws Exception {
        if (process.isAlive()) {
            requestGracefulExit(process);
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Could not terminate the disposable Master process");
            }
        }
    }
}
