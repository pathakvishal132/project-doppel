package com.azentio.doppel.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.azentio.doppel.config.DoppelProperties;

/** Masks and pseudonymises registrant account numbers. The full number is never stored or logged. */
@Component
public class AccountProtector {

    private static final int VISIBLE_DIGITS = 4;

    private final SecretKeySpec key;

    public AccountProtector(DoppelProperties properties) {
        if (properties.hmacSecret() == null || properties.hmacSecret().isBlank()) {
            throw new IllegalStateException("doppel.hmac-secret (DOPPEL_HMAC_SECRET) must be set");
        }
        this.key = new SecretKeySpec(properties.hmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** "123456789012" → "XXXXXXXX9012". */
    public String mask(String account) {
        if (account == null || account.length() <= VISIBLE_DIGITS) {
            return "X".repeat(account == null ? 0 : account.length());
        }
        int hidden = account.length() - VISIBLE_DIGITS;
        return "X".repeat(hidden) + account.substring(hidden);
    }

    /** Hex-encoded HMAC-SHA256 of the account, usable to correlate registrants without the raw number. */
    public String hmac(String account) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(account.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
