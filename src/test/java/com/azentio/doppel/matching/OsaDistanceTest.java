package com.azentio.doppel.matching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OsaDistanceTest {

    @Test
    void computesEditsIncludingAdjacentTransposition() {
        assertThat(OsaDistance.between("shopkart", "shopkart")).isZero();
        assertThat(OsaDistance.between("shopkrat", "shopkart")).isEqualTo(1); // transposition
        assertThat(OsaDistance.between("shpkart", "shopkart")).isEqualTo(1);  // deletion
        assertThat(OsaDistance.between("shopkartt", "shopkart")).isEqualTo(1); // insertion
        assertThat(OsaDistance.between("shopcart", "shopkart")).isEqualTo(1); // substitution
        assertThat(OsaDistance.between("zentrabnc", "zentrabank")).isEqualTo(2);
        assertThat(OsaDistance.between("", "abc")).isEqualTo(3);
    }
}
