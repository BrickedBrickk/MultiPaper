package puregero.multipaper.commands;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MPDebugCommandTest {

    @Test
    void reportsRuntimeFailuresInsteadOfLettingThemEscapeTheCommand() {
        RuntimeException failure = new IllegalStateException("unloaded chunk");
        AtomicReference<RuntimeException> reported = new AtomicReference<>();

        boolean completed = MPDebugCommand.runSafely(() -> {
            throw failure;
        }, reported::set);

        assertFalse(completed);
        assertSame(failure, reported.get());
    }

    @Test
    void reportsSuccessfulDebugUpdatesAsCompleted() {
        AtomicReference<RuntimeException> reported = new AtomicReference<>();

        boolean completed = MPDebugCommand.runSafely(() -> {}, reported::set);

        assertTrue(completed);
        assertNull(reported.get());
    }
}
