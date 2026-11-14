package com.azentio.doppel.matching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextNormalizerTest {

    @Test
    void appliesNfkcAndLowercase() {
        assertThat(TextNormalizer.normalize("ＺＥＮＴＲＡ Bank")).isEqualTo("zentra bank");
    }

    @Test
    void compactRemovesSpecialCharacters() {
        assertThat(TextNormalizer.compact("Zentra.Bank-Refund_01 !")).isEqualTo("zentrabankrefund01");
    }

    @Test
    void skeletonFoldsCyrillicGreekDigitsAndLigatures() {
        assertThat(TextNormalizer.skeleton("Zеntrа")).isEqualTo("zentra");      // Cyrillic е, а
        assertThat(TextNormalizer.skeleton("Νοvapay")).isEqualTo("novapay");     // Greek Ν, ο
        assertThat(TextNormalizer.skeleton("z3ntr4b4nk")).isEqualTo("zentrabank");
        assertThat(TextNormalizer.skeleton("sh0pk4rt")).isEqualTo("shopkart");
        assertThat(TextNormalizer.skeleton("e1ectra")).isEqualTo("electra");
        assertThat(TextNormalizer.skeleton("rnedicore")).isEqualTo("medicore");  // rn -> m
        assertThat(TextNormalizer.skeleton("bluevvave")).isEqualTo("bluewave");  // vv -> w
    }
}
