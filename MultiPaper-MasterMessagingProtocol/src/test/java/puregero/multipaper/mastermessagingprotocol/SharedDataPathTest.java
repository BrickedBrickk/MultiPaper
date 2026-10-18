package puregero.multipaper.mastermessagingprotocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SharedDataPathTest {
    @TempDir
    Path tempDir;

    @Test
    void mapsDifferentBackendInstallPathsToOneSharedWorldPath() throws Exception {
        final Path firstBackend = tempDir.resolve("backend-one/world");
        final Path secondBackend = tempDir.resolve("different-root/backend-two/world");
        Files.createDirectories(firstBackend.resolve("dimensions/minecraft/overworld/data/paper"));
        Files.createDirectories(secondBackend.resolve("dimensions/minecraft/overworld/data/paper"));
        // Backends keep a session lock but the Master owns level.dat.
        Files.createFile(firstBackend.resolve("session.lock"));
        Files.createFile(secondBackend.resolve("level.dat"));

        assertEquals("world/dimensions/minecraft/overworld/data/paper/metadata.dat",
            SharedDataPath.fromLocalPath(firstBackend.resolve("dimensions/minecraft/overworld/data/paper/metadata.dat").toString()));
        assertEquals("world/dimensions/minecraft/overworld/data/paper/metadata.dat",
            SharedDataPath.fromLocalPath(secondBackend.resolve("dimensions/minecraft/overworld/data/paper/metadata.dat").toString()));
    }

    @Test
    void mapsBackendWorldWithoutLocalLevelDat() throws Exception {
        final Path backendWorld = tempDir.resolve("backend/world");
        final Path savedData = backendWorld.resolve("dimensions/minecraft/the_end/data/minecraft/world_clocks.dat");
        Files.createDirectories(savedData.getParent());
        Files.createFile(backendWorld.resolve("session.lock"));

        assertEquals("world/dimensions/minecraft/the_end/data/minecraft/world_clocks.dat",
            SharedDataPath.fromLocalPath(savedData.toString()));
    }

    @Test
    void rejectsAbsoluteAndParentTraversalPathsFromTheMaster() {
        assertThrows(IOException.class, () -> SharedDataPath.resolveSharedPath("../outside.dat"));
        assertThrows(IOException.class, () -> SharedDataPath.resolveSharedPath("/absolute/world/data/file.dat"));
        assertThrows(IOException.class, () -> SharedDataPath.resolveSharedPath("world/../../outside.dat"));
    }
}
