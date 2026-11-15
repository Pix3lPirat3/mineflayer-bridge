package io.github.pix3lpirat3.mfbridge.core.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.pix3lpirat3.mfbridge.core.util.Encoding;
import io.github.pix3lpirat3.mfbridge.core.util.SecureFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;

/** Writes {@code endpoint.json} for local discovery. */
public final class EndpointFile {
    /** File name inside the bridge directory. */
    public static final String NAME = "endpoint.json";

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private EndpointFile() {}

    /**
     * Writes the endpoint file atomically and owner-only. The URL always names loopback, because the
     * file is only for local discovery; it uses {@code wss://} when plain loopback is turned off.
     *
     * @param bridgeDir {@code <config>/mineflayer_bridge}
     * @param port the bound port
     * @param secure whether local plugins must use TLS
     * @param fingerprint the instance certificate fingerprint, {@code sha256:<hex>}
     * @param localToken the 32-byte token generated for this start
     * @return the written file
     * @throws IOException when the file cannot be written
     */
    public static Path write(Path bridgeDir, int port, boolean secure, String fingerprint, byte[] localToken,
            Logger log) throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("v", 1);
        json.put("url", (secure ? "wss" : "ws") + "://127.0.0.1:" + port + "/bridge/v1");
        json.put("fingerprint", fingerprint);
        json.put("localToken", Encoding.base64Url(localToken));
        Path file = bridgeDir.resolve(NAME);
        SecureFiles.writeAtomically(file, GSON.toJson(json).getBytes(StandardCharsets.UTF_8), true, log);
        return file;
    }
}
