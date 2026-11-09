package studio.dreamys.prometheus.essential.verity;

import gg.essential.network.connectionmanager.Connection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class VerityChecksTest {
    @Test
    void redirectPointsNewConnectionsAtTheReplayServer() throws Exception {
        Field field = Connection.class.getDeclaredField("CM_HOST_URI");
        field.setAccessible(true);
        Object before = field.get(null);
        try {
            URI replay = URI.create("ws://127.0.0.1:47801/v1");
            VerityChecks.redirect(replay);
            assertEquals(replay, field.get(null));
            assertThrows(IllegalArgumentException.class, () -> VerityChecks.redirect(URI.create("wss://example.com/v1")));
        } finally {
            field.set(null, before);
        }
    }
}
