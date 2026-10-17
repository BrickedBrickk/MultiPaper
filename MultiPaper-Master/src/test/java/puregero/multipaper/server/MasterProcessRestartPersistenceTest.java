package puregero.multipaper.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WriteChunkMessage;
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
    private static final int CHUNK_X = -33;
    private static final int CHUNK_Z = 48;

    @TempDir
    Path tempDir;

    @Test
    @Timeout(60)
    void chunkDataSurvivesGracefulShutdownAndMasterJarRestart() throws Exception {
        int masterPort = findFreePort();
        int proxyPort = findFreePort();
        assertTrue(masterPort != proxyPort, "Master and proxy need distinct ports");
        Path masterDirectory = tempDir.resolve("master");
        masterDirectory.toFile().mkdirs();

        byte[] expected = "persisted-across-master-process-restart".getBytes(StandardCharsets.UTF_8);
        Process firstMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient firstClient = null;
        try {
            awaitListening(firstMaster, masterPort);
            firstClient = TestServerClient.connect(masterPort, "restart-writer");
            ServerBoundMessage writeReply = firstClient.request(new WriteChunkMessage(WORLD, "region", CHUNK_X, CHUNK_Z, expected));
            assertTrue(((BooleanMessageReply) writeReply).result, "Master must acknowledge the chunk write");
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
            ServerBoundMessage readReply = secondClient.request(new ReadChunkMessage(WORLD, "region", CHUNK_X, CHUNK_Z));
            assertTrue(readReply instanceof DataMessageReply, "Restarted Master should return stored chunk bytes");
            assertArrayEquals(expected, ((DataMessageReply) readReply).data);

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
    void chunkDataSurvivesUnexpectedMasterTerminationAfterWriteAcknowledgement() throws Exception {
        int masterPort = findFreePort();
        int proxyPort = findFreePort();
        assertTrue(masterPort != proxyPort, "Master and proxy need distinct ports");
        Path masterDirectory = tempDir.resolve("crashed-master");
        masterDirectory.toFile().mkdirs();

        byte[] expected = "persisted-before-unexpected-master-termination".getBytes(StandardCharsets.UTF_8);
        Process firstMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient firstClient = null;
        try {
            awaitListening(firstMaster, masterPort);
            firstClient = TestServerClient.connect(masterPort, "crash-writer");
            ServerBoundMessage writeReply = firstClient.request(new WriteChunkMessage(WORLD, "region", CHUNK_X, CHUNK_Z, expected));
            assertTrue(((BooleanMessageReply) writeReply).result, "Master must acknowledge the chunk write before termination");
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
            ServerBoundMessage readReply = reader.request(new ReadChunkMessage(WORLD, "region", CHUNK_X, CHUNK_Z));
            assertTrue(readReply instanceof DataMessageReply, "Recovered Master should return stored chunk bytes");
            assertArrayEquals(expected, ((DataMessageReply) readReply).data);

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
