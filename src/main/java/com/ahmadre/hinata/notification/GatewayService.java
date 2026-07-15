package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.config.HinataProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client for Hinata Connect, the single central service that the published client app
 * depends on (see {@link HinataProperties.Gateway}). The server registers once on
 * boot (idempotent by API base URL) and then uses the returned {@code serverId} +
 * {@code secret} to:
 *
 *  - sign universal-link relay URLs for invite / password-reset emails, and
 *  - authenticate push fan-out (the gateway owns the app's FCM credentials).
 *
 * Self-contained JSON (no Jackson dependency) keeps this decoupled from the
 * server's serialization stack. If the gateway is unreachable, relay links fall
 * back to the server's own web app and push simply no-ops.
 */
@Service
@RequiredArgsConstructor
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final long LINK_TTL_SECONDS = 7L * 24 * 3600;

    private final HinataProperties props;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private volatile String serverId;
    private volatile String secret;

    public enum PushResult { SENT, DEAD, FAILED, DISABLED }

    @PostConstruct
    void register() {
        if (!props.getGateway().isEnabled()) {
            log.info("Hinata Connect disabled — push + universal-link relay are off.");
            return;
        }
        try {
            String body = "{\"apiBaseUrl\":" + jstr(props.getBaseUrl())
                    + ",\"webBaseUrl\":" + jstr(props.webBase()) + "}";
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(gw() + "/register"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            String bs = props.getGateway().getBootstrapSecret();
            if (bs != null && !bs.isBlank()) b.header("X-Bootstrap-Secret", bs);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 == 2) {
                serverId = extract(r.body(), "serverId");
                secret = extract(r.body(), "secret");
                log.info("Registered with Hinata Connect ({}) as server {}.", gw(), serverId);
            } else {
                log.warn("Hinata Connect registration failed: HTTP {} {}", r.statusCode(), r.body());
            }
        } catch (Exception e) {
            log.warn("Hinata Connect registration error: {} (push + relay will retry lazily).", e.getMessage());
        }
    }

    public boolean registered() {
        return serverId != null && secret != null;
    }

    /**
     * A signed universal-link relay URL for an email deep link (invite / reset),
     * which the app resolves locally and the gateway redirects for the web. Falls
     * back to the server's own web app if the gateway isn't available.
     */
    public String relayLink(String path, String token) {
        if (!registered()) {
            register();
            if (!registered()) return directFallback(path, token);
        }
        long exp = Instant.now().getEpochSecond() + LINK_TTL_SECONDS;
        String payload = "{\"sid\":" + jstr(serverId)
                + ",\"a\":" + jstr(props.getBaseUrl())
                + ",\"u\":" + jstr(props.webBase())
                + ",\"p\":" + jstr(path)
                + ",\"t\":" + jstr(token)
                + ",\"e\":" + exp + "}";
        String code = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String sig = B64.encodeToString(hmac(code));
        return gw() + "/l/" + code + "." + sig;
    }

    private String directFallback(String path, String token) {
        return props.webBase() + path + "?token=" + enc(token) + "&server=" + enc(props.getBaseUrl());
    }

    /** Send one push to a device token via the gateway. */
    public PushResult push(String token, String title, String body, String link) {
        if (!props.getGateway().isEnabled()) return PushResult.DISABLED;
        if (!registered()) {
            register();
            if (!registered()) return PushResult.FAILED;
        }
        try {
            String data = (link != null && !link.isBlank()) ? ",\"data\":{\"link\":" + jstr(link) + "}" : "";
            String json = "{\"token\":" + jstr(token) + ",\"title\":" + jstr(title)
                    + ",\"body\":" + jstr(body) + data + "}";
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(gw() + "/push/send"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("X-Server-Id", serverId)
                    .header("X-Server-Secret", secret)
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                    HttpResponse.BodyHandlers.ofString());
            int sc = r.statusCode();
            if (sc / 100 == 2) return PushResult.SENT;
            if (sc == 404) return PushResult.DEAD;
            if (sc == 401) { serverId = null; secret = null; } // creds invalid — re-register next time
            log.warn("Gateway push failed: HTTP {} {}", sc, r.body());
            return PushResult.FAILED;
        } catch (Exception e) {
            log.warn("Gateway push error: {}", e.getMessage());
            return PushResult.FAILED;
        }
    }

    private String gw() {
        String u = props.getGateway().getBaseUrl();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private byte[] hmac(String data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return m.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    /** Minimal JSON string literal (handles the characters that occur in our values). */
    private static String jstr(String v) {
        if (v == null) return "null";
        StringBuilder s = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"' -> s.append("\\\"");
                case '\\' -> s.append("\\\\");
                case '\n' -> s.append("\\n");
                case '\r' -> s.append("\\r");
                case '\t' -> s.append("\\t");
                default -> {
                    if (c < 0x20) s.append(String.format("\\u%04x", (int) c));
                    else s.append(c);
                }
            }
        }
        return s.append('"').toString();
    }

    private static String extract(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json == null ? "" : json);
        return m.find() ? m.group(1) : null;
    }
}
