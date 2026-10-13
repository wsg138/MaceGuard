package com.lincoln.maceguard.website;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebsiteSignedExport {
    private WebsiteSignedExport() { }
    public static URI endpoint(String value) {
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !"/api/community/ingest".equals(uri.getRawPath()))
            throw new IllegalArgumentException("Invalid website ingest endpoint");
        return uri;
    }
    public static HttpRequest request(URI endpoint, String secret, String body, long time, String nonce) {
        endpoint(endpoint.toString());
        if (secret.length() < 32 || !nonce.matches("[A-Za-z0-9_-]{16,80}")
                || body.getBytes(StandardCharsets.UTF_8).length > 150_000)
            throw new IllegalArgumentException("Invalid website export request");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signed = time + "\n" + nonce + "\n" + endpoint.getRawPath() + "\n" + body;
            String signature = HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
            return HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("x-enthusia-timestamp", Long.toString(time))
                    .header("x-enthusia-nonce", nonce).header("x-enthusia-signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException("Signing unavailable", error);
        }
    }
}
