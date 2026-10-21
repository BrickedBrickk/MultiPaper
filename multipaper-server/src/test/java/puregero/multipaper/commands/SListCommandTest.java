package puregero.multipaper.commands;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import puregero.multipaper.permissions.MultiPaperCommandPermissions;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SListCommandTest {

    @Test
    void usesDedicatedMultiPaperPermission() {
        assertEquals("multipaper.command.slist", new SListCommand("slist").getPermission());
    }

    @Test
    void registersPermissionForRegularPlayersByDefault() {
        PluginManager pluginManager = mock(PluginManager.class);
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.getPermissionSubscriptions(anyString())).thenReturn(Collections.emptySet());
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            Permission parent = new Permission("test.commands", "Test command permissions");

            MultiPaperCommandPermissions.registerPermissions(parent);

            ArgumentCaptor<Permission> permissions = ArgumentCaptor.forClass(Permission.class);
            verify(pluginManager, atLeastOnce()).addPermission(permissions.capture());
            Permission slist = permissions.getAllValues().stream()
                    .filter(permission -> permission.getName().equals("multipaper.command.slist"))
                    .findFirst()
                    .orElse(null);

            assertNotNull(slist, "The /slist permission must be registered");
            assertEquals(PermissionDefault.TRUE, slist.getDefault());
        }
    }
}
