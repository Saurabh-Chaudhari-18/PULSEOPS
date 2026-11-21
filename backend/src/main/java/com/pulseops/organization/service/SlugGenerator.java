package com.pulseops.organization.service;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Builds URL-safe organization slugs: a readable prefix from the name plus a random suffix.
 * The suffix makes collisions negligible without having to probe other tenants' slugs —
 * which RLS correctly prevents. Output always satisfies the DB CHECK
 * {@code ^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$}.
 */
@Component
public class SlugGenerator {

    static final int MAX_BASE = 40;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String generate(String name) {
        return base(name) + "-" + randomSuffix();
    }

    static String base(String name) {
        String ascii = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (ascii.length() > MAX_BASE) {
            ascii = ascii.substring(0, MAX_BASE).replaceAll("-+$", "");
        }
        return ascii.isEmpty() ? "org" : ascii;
    }

    private static String randomSuffix() {
        byte[] bytes = new byte[3];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
