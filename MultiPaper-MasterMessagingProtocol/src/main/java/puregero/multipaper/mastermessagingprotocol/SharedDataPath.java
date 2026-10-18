package puregero.multipaper.mastermessagingprotocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Converts backend-local saved-data paths into paths shared by every backend.
 * The Master resolves the resulting {@code <world>/<relative path>} from its
 * working directory, so backend installation directories may differ.
 */
public final class SharedDataPath {
    private SharedDataPath() {
    }

    public static String fromLocalPath(String localPath) throws IOException {
        final Path path = Path.of(localPath).toAbsolutePath().normalize();
        Path worldDirectory = path.getParent();
        while (worldDirectory != null && !Files.isRegularFile(worldDirectory.resolve("level.dat"))) {
            worldDirectory = worldDirectory.getParent();
        }
        if (worldDirectory == null || worldDirectory.getFileName() == null) {
            throw new IOException("Could not find the world root for saved-data path " + path);
        }

        final Path relativePath = worldDirectory.relativize(path);
        if (relativePath.getNameCount() == 0 || relativePath.startsWith("..")) {
            throw new IOException("Saved-data path is outside its world root: " + path);
        }
        return worldDirectory.getFileName() + "/" + relativePath.toString().replace('\\', '/');
    }

    public static Path resolveSharedPath(String sharedPath) throws IOException {
        if (sharedPath.startsWith("/") || sharedPath.startsWith("\\") || sharedPath.indexOf('\\') >= 0
            || sharedPath.matches("^[A-Za-z]:.*")) {
            throw new IOException("Invalid shared saved-data path: " + sharedPath);
        }
        final Path path = Path.of(sharedPath).normalize();
        if (path.isAbsolute() || path.getNameCount() < 2 || path.startsWith("..")) {
            throw new IOException("Invalid shared saved-data path: " + sharedPath);
        }
        return path;
    }

    public static String normalizeSharedPath(String sharedPath) throws IOException {
        return resolveSharedPath(sharedPath).toString().replace('\\', '/');
    }
}
