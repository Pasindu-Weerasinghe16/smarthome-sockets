package smarthome.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 4-byte length-prefixed UTF-8 JSON frames over TCP. */
public final class FrameIO {
    private FrameIO() {}

    public static void writeJsonFrame(DataOutputStream out, Object msg) throws IOException {
        byte[] payload = Json.toJson(msg).getBytes(StandardCharsets.UTF_8);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    public static String readJsonFrame(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > (16 * 1024 * 1024)) throw new IOException("Invalid frame length: " + len);
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) throw new IOException("Stream closed mid-frame");
            off += r;
        }
        return new String(buf, StandardCharsets.UTF_8);
    }
}
