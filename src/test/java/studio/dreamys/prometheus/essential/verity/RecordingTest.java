package studio.dreamys.prometheus.essential.verity;

import gg.essential.network.connectionmanager.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecordingTest {
    private static String send(String name, String payload, String id) {
        return "{\"type\": \"SEND\", \"name\": \"" + name + "\", \"payload\": " + payload + ", \"id\": \"" + id + "\"}";
    }

    private static String recv(String name, String payload, String id) {
        return "{\"type\": \"RECV\", \"name\": \"" + name + "\", \"payload\": " + payload + ", \"id\": \"" + id + "\"}";
    }

    @Test
    void keepsCosmeticsAndDropsTheRestOfTheAccount() {
        String open = "{\"type\": \"OPEN\", \"version\": 12}";
        String cosmetics = recv("cosmetic.ServerCosmeticsPopulatePacket", "{\"a\":[]}", "");
        String outfits = recv("cosmetic.outfit.ServerCosmeticOutfitPopulatePacket", "{\"a\":[]}", "");
        String wardrobe = recv("wardrobe.ServerWardrobeSettingsPacket", "{}", "");
        String disabled = recv("features.ServerDisabledFeaturesPacket", "{}", "");
        List<String> kept = Recording.filter(Arrays.asList(
                open,
                recv("connection.ConnectionRegisterPacketTypeIdPacket", "{\"a\":\"chat.X\",\"b\":1}", ""),
                send("connection.ConnectionRegisterPacketTypeIdPacket", "{\"a\":\"cosmetic.Y\",\"b\":1}", ""),
                recv("connection.ConnectionKeepAlivePacket", "{}", "k1"),
                send("connection.ConnectionKeepAlivePacket", "{}", "k1"),
                cosmetics, outfits, wardrobe, disabled,
                recv("chat.ServerChatChannelMessagePacket", "{\"a\":\"hi\"}", ""),
                recv("relationships.ServerRelationshipPopulatePacket", "{}", ""),
                recv("profile.ServerProfileStatusPacket", "{}", ""),
                recv("media.ServerMediaPopulatePacket", "{}", ""),
                recv("skin.ServerSkinPopulatePacket", "{}", ""),
                send("telemetry.ClientTelemetryPacket", "{}", "t1"),
                recv("response.ResponseActionPacket", "{}", "t1")));
        assertEquals(Arrays.asList(open, cosmetics, outfits, wardrobe, disabled), kept);
    }

    @Test
    void keepsRequestsAndTheirRepliesEvenUnderOtherNames() {
        String request = send("cosmetic.ClientCosmeticRequestPacket", "{\"a\":[\"X\"],\"b\":[]}", "r1");
        String reply = recv("cosmetic.ServerCosmeticsPopulatePacket", "{\"a\":[]}", "r1");
        String ack = send("wardrobe.ClientWardrobeSettingsPacket", "{}", "r2");
        String ackReply = recv("response.ResponseActionPacket", "{\"a\":true}", "r2");
        String otherReply = recv("response.ResponseActionPacket", "{\"a\":true}", "r3");
        assertEquals(Arrays.asList(request, reply, ack, ackReply),
                Recording.filter(Arrays.asList(request, reply, ack, ackReply, otherReply)));
    }

    @Test
    void skipsLinesThatAreNotJsonObjects() {
        String cosmetics = recv("cosmetic.ServerCosmeticTypesPopulatePacket", "{}", "");
        assertEquals(Arrays.asList(cosmetics), Recording.filter(Arrays.asList("", "garbage", "[1]", cosmetics)));
    }

    @Test
    void picksTheNewestPlainLog(@TempDir Path dir) throws Exception {
        assertNull(Recording.newestLog(dir.resolve("missing")));
        Path old = Files.write(dir.resolve("2026-01-01.log"), new byte[0]);
        Path newer = Files.write(dir.resolve("2026-01-02.log"), new byte[0]);
        Path gz = Files.write(dir.resolve("2026-01-03.log.gz"), new byte[0]);
        Files.setLastModifiedTime(old, FileTime.fromMillis(1000));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2000));
        Files.setLastModifiedTime(gz, FileTime.fromMillis(3000));
        assertEquals(newer, Recording.newestLog(dir));
    }

    @Test
    void recordWritesTheFilteredLines(@TempDir Path dir) throws Exception {
        String cosmetics = recv("cosmetic.ServerCosmeticTypesPopulatePacket", "{}", "");
        Path log = Files.write(dir.resolve("a.log"), Arrays.asList(cosmetics,
                recv("chat.ServerChatChannelMessagePacket", "{}", "")), StandardCharsets.UTF_8);
        assertEquals(cosmetics + "\n", new String(Recording.record(log), StandardCharsets.UTF_8));
    }

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
