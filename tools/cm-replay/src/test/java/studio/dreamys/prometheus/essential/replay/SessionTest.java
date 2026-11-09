package studio.dreamys.prometheus.essential.replay;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionTest {
    static String line(String type, String name, String payload, String id) {
        return "{\"type\": \"" + type + "\", \"name\": \"" + name + "\", \"payload\": " + payload + ", \"id\": \"" + id + "\"}";
    }

    static final List<String> RECORDING = List.of(
            "{\"type\": \"OPEN\", \"version\": 11}",
            line("RECV", "connection.ConnectionRegisterPacketTypeIdPacket", "{\"a\":\"cosmetic.X\",\"b\":1}", ""),
            line("RECV", "cosmetic.ServerCosmeticTypesPopulatePacket", "{\"a\":[1]}", ""),
            line("SEND", "cosmetic.ClientCosmeticRequestPacket", "{\"a\":[\"B\",\"A\"],\"b\":[]}", "r1"),
            line("RECV", "cosmetic.ServerCosmeticsPopulatePacket", "{\"a\":[\"legacy\"]}", "r1"),
            line("SEND", "cosmetic.categories.ClientCosmeticCategoriesRequestPacket", "{\"a\":[\"hats\"]}", "c1"),
            line("SEND", "cosmetic.categories.ClientCosmeticCategoriesRequestPacket", "{\"a\":[\"capes\"]}", "c2"),
            line("RECV", "cosmetic.categories.ServerCosmeticCategoriesPopulatePacket", "{\"a\":\"hats\"}", "c1"),
            line("RECV", "cosmetic.categories.ServerCosmeticCategoriesPopulatePacket", "{\"a\":\"capes\"}", "c2"),
            line("RECV", "cosmetic.outfit.ServerCosmeticOutfitPopulatePacket", "{\"a\":[]}", "unknown-request"),
            line("RECV", "connection.ConnectionKeepAlivePacket", "{}", "k"));

    @Test
    void splitsPushesFromReplies() {
        Session s = Session.parse(RECORDING);
        assertEquals(11, s.protocolVersion);
        assertEquals(List.of(new Session.Packet("cosmetic.ServerCosmeticTypesPopulatePacket", "{\"a\":[1]}"),
                        new Session.Packet("cosmetic.outfit.ServerCosmeticOutfitPopulatePacket", "{\"a\":[]}")),
                s.pushes);
    }

    @Test
    void matchesRequestsByPayloadInAnyOrder() {
        Session s = Session.parse(RECORDING);
        assertEquals(List.of(new Session.Packet("cosmetic.ServerCosmeticsPopulatePacket", "{\"a\":[\"legacy\"]}")),
                s.repliesTo("cosmetic.ClientCosmeticRequestPacket", "{\"b\":[],\"a\":[\"A\",\"B\"]}").orElseThrow());
        assertEquals("{\"a\":\"capes\"}", s.repliesTo("cosmetic.categories.ClientCosmeticCategoriesRequestPacket",
                "{\"a\":[\"capes\"]}").orElseThrow().get(0).json());
    }

    @Test
    void fallsBackToTheOnlyRequestOfThatName() {
        Session s = Session.parse(RECORDING);
        // A client missing a slightly different set of legacy cosmetics still gets the recorded answer...
        assertTrue(s.repliesTo("cosmetic.ClientCosmeticRequestPacket", "{\"a\":[\"A\"],\"b\":[]}").isPresent());
        // ...but with several recorded requests of a name, only an exact match answers.
        assertTrue(s.repliesTo("cosmetic.categories.ClientCosmeticCategoriesRequestPacket", "{\"a\":[\"emotes\"]}").isEmpty());
        assertTrue(s.repliesTo("chat.ClientChatChannelMessagesRetrievePacket", "{}").isEmpty());
    }

    @Test
    void defaultsToProtocol12WithoutAnOpenLine() {
        assertEquals(12, Session.parse(List.of()).protocolVersion);
    }

    @Test
    void canonicalSortsKeysAndPlainArraysOnly() {
        assertEquals(Session.canonical(JsonParser.parseString("{\"b\":[2,1],\"a\":{\"y\":1,\"x\":2}}")),
                Session.canonical(JsonParser.parseString("{\"a\":{\"x\":2,\"y\":1},\"b\":[1,2]}")));
        assertNotEquals(Session.canonical(JsonParser.parseString("[{\"a\":1},{\"a\":2}]")),
                Session.canonical(JsonParser.parseString("[{\"a\":2},{\"a\":1}]")));
    }
}
