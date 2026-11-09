package studio.dreamys.prometheus.essential.replay;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * One connection-manager packet as Essential's {@code ConnectionCodec} puts it in a binary WebSocket frame, big-endian:
 * {@code int typeId, int length + UTF-8 uniqueId ("" for none), int length + UTF-8 JSON}.
 */
record Frame(int typeId, String uniqueId, String json) {
    /** Type id 0 is fixed: {@code connection.ConnectionRegisterPacketTypeIdPacket}, {@code {"a": name, "b": id}}. */
    static final int REGISTER = 0;
    static final String REGISTER_NAME = "connection.ConnectionRegisterPacketTypeIdPacket";
    static final String KEEP_ALIVE = "connection.ConnectionKeepAlivePacket";

    byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(typeId);
            writeString(out, uniqueId == null ? "" : uniqueId);
            writeString(out, json);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Frame decode(ByteBuffer buffer) {
        int typeId = buffer.getInt();
        String uniqueId = readString(buffer);
        String json = readString(buffer);
        return new Frame(typeId, uniqueId, json);
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readString(ByteBuffer buffer) {
        byte[] b = new byte[buffer.getInt()];
        buffer.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
