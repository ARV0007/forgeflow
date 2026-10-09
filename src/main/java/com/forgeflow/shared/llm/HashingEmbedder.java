package com.forgeflow.shared.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An embedder with no model: feature hashing. Each word - and each part of a
 * camelCase or snake_case identifier - is hashed to one of 768 slots, and the
 * vector counts them (then is normalised to length 1).
 *
 * Texts sharing words land close together; that's all it knows. No sense of
 * meaning - "button" and "click" are unrelated to it. But it is deterministic,
 * free, needs no network, and is honest about what it is. Used by tests, and
 * by anyone running ForgeFlow without an API key.
 */
public class HashingEmbedder implements Embedder {

    /**
     * Acronyms first, then capitalised or lower-case words, then numbers - so
     * "HTMLElement" splits as html + element and "renderTodos" as render + todos.
     * (Order matters: put the word branch first and "HTML" comes out as four
     * single letters.)
     */
    private static final Pattern WORD = Pattern.compile("[A-Z]+(?![a-z])|[A-Z]?[a-z]+|[0-9]+");

    @Override
    public List<float[]> embed(List<String> texts, Kind kind) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            out.add(embedOne(t));
        }
        return out;
    }

    @Override
    public String modelName() {
        return "hashing-768";
    }

    static float[] embedOne(String text) {
        float[] v = new float[DIMENSIONS];
        Matcher m = WORD.matcher(text == null ? "" : text);
        while (m.find()) {
            String token = m.group().toLowerCase(Locale.ROOT);
            if (token.length() < 2) {
                continue;
            }
            int h = hash(token);
            // The sign bit spreads collisions: two words sharing a slot cancel
            // as often as they add, instead of always inflating it.
            v[Math.floorMod(h, DIMENSIONS)] += (h & 0x80000000) == 0 ? 1f : -1f;
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm > 0) {
            float inv = (float) (1 / Math.sqrt(norm));
            for (int i = 0; i < v.length; i++) {
                v[i] *= inv;
            }
        }
        return v;
    }

    private static int hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return ((d[0] & 0xff) << 24) | ((d[1] & 0xff) << 16) | ((d[2] & 0xff) << 8) | (d[3] & 0xff);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
