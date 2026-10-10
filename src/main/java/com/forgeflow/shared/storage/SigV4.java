package com.forgeflow.shared.storage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * AWS Signature Version 4, for S3 and anything that speaks it (MinIO,
 * Cloudflare R2, Ceph). No SDK: the algorithm is four steps, and owning it
 * means owning the one part of an object-store client that's easy to get
 * subtly wrong.
 *
 *   1. canonical request  method, path, query, sorted lower-case headers,
 *                         the list of signed headers, the payload's SHA-256
 *   2. string to sign     algorithm, timestamp, scope (date/region/service),
 *                         SHA-256 of step 1
 *   3. signing key        HMAC chain: "AWS4"+secret -> date -> region ->
 *                         service -> "aws4_request"
 *   4. signature          HMAC(signing key, step 2), hex
 *
 * Verified against the worked example in AWS's S3 documentation (SigV4Test).
 */
public final class SigV4 {

    public static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    public static final String UNSIGNED = "UNSIGNED-PAYLOAD";

    private SigV4() {
    }

    /**
     * @param path      already URI-encoded (see {@link #encodePath})
     * @param query     canonical query string ("" for none)
     * @param headers   every header to sign, including host, x-amz-date and
     *                  x-amz-content-sha256; any case
     * @param amzDate   yyyyMMdd'T'HHmmss'Z', the same value as the x-amz-date header
     * @return the value of the Authorization header
     */
    public static String authorization(String method, String path, String query, Map<String, String> headers,
                                       String payloadSha256, String accessKey, String secretKey,
                                       String region, String service, String amzDate) {
        TreeMap<String, String> canonical = new TreeMap<>();
        headers.forEach((k, v) -> canonical.put(k.toLowerCase(Locale.ROOT), v.trim().replaceAll("\\s+", " ")));
        String signedHeaders = String.join(";", canonical.keySet());
        String canonicalHeaders = canonical.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue() + "\n")
                .collect(Collectors.joining());

        String canonicalRequest = method + "\n" + path + "\n" + query + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadSha256;

        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest);

        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, service);
        key = hmac(key, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(key, stringToSign));

        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    /** S3's rule: encode every byte except unreserved characters and '/'. Never double-encode. */
    public static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || c == '/') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return sb.toString();
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String s) {
        return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
