package com.azentio.doppel.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.azentio.doppel.config.DoppelProperties;

class AccountProtectorTest {

    private static AccountProtector protector(String secret) {
        return new AccountProtector(new DoppelProperties(secret, new DoppelProperties.Scores(50, 60, 40, 20),
                new DoppelProperties.Thresholds(70, 40), List.of()));
    }

    @Test
    void masksAllButLastFourDigits() {
        AccountProtector p = protector("k");
        assertThat(p.mask("123456789012")).isEqualTo("XXXXXXXX9012");
        assertThat(p.mask("123456789")).isEqualTo("XXXXX6789");
        assertThat(p.mask("123456789012345678")).isEqualTo("XXXXXXXXXXXXXX5678");
        assertThat(p.mask("123456789012")).doesNotContain("12345678");
    }

    @Test
    void hmacMatchesKnownSha256TestVector() {
        // Well-known HMAC-SHA256 vector: key="key", message="The quick brown fox jumps over the lazy dog"
        assertThat(protector("key").hmac("The quick brown fox jumps over the lazy dog"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void hmacIsDeterministicKeyedAndDoesNotContainAccount() {
        String ref = protector("secret-a").hmac("123456789012");
        assertThat(ref).hasSize(64).matches("[0-9a-f]{64}").doesNotContain("123456789012");
        assertThat(protector("secret-a").hmac("123456789012")).isEqualTo(ref);
        assertThat(protector("secret-b").hmac("123456789012")).isNotEqualTo(ref);
        assertThat(protector("secret-a").hmac("123456789013")).isNotEqualTo(ref);
    }

    @Test
    void blankSecretIsRejected() {
        assertThatThrownBy(() -> protector(" ")).isInstanceOf(IllegalStateException.class);
    }
}
