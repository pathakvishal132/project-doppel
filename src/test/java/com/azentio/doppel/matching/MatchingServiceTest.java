package com.azentio.doppel.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.azentio.doppel.TestFixtures;
import com.azentio.doppel.brand.Brand;

class MatchingServiceTest {

    private final MatchingService service = new MatchingService(TestFixtures.properties());
    private final List<Brand> brands = TestFixtures.mandatoryBrands();

    private MatchResult match(String vpa, String displayName) {
        return service.match(vpa, displayName, brands);
    }

    @Nested
    class OfficialHandles {

        @ParameterizedTest
        @CsvSource({
                "zentrabank@zbk, Zentra Bank Refund Helpdesk, 1",
                "zentra.support@zbk, Zentra Support, 1",
                "shopkart@kotak, ShopKart, 2",
                "shopkart.refunds@kotak, ShopKart Refunds, 2",
                "electrapower.bills@axis, Electra Power KYC Update, 3",
                "ZentraBank@ZBK, Zentra Bank, 1"
        })
        void officialVpaAlwaysAllowsWithScoreZero(String vpa, String displayName, long brandId) {
            MatchResult r = match(vpa, displayName);
            assertThat(r.decision()).isEqualTo(Decision.ALLOW);
            assertThat(r.score()).isZero();
            assertThat(r.brandId()).isEqualTo(brandId);
            assertThat(r.reasons()).containsExactly(MatchingService.OFFICIAL_VPA);
        }

        @Test
        void sameHandleOnDifferentPspIsNotOfficial() {
            MatchResult r = match("zentrabank@ybl", "Zentra Bank");
            assertThat(r.score()).isPositive();
            assertThat(r.reasons()).doesNotContain(MatchingService.OFFICIAL_VPA);
        }
    }

    @Nested
    class MandatoryBrands {

        @Test
        void zentraBankAssignmentExampleIsBlocked() {
            MatchResult r = match("zentrabank.refund@okaxis", "Zentra Bank Refunds");
            assertThat(r.brandName()).isEqualTo("Zentra Bank");
            assertThat(r.score()).isEqualTo(70); // BRAND_EXACT 50 + LURE 20
            assertThat(r.decision()).isEqualTo(Decision.BLOCK);
        }

        @Test
        void shopKartImpersonationIsDetected() {
            MatchResult r = match("shopkart.orders@ybl", "Orders");
            assertThat(r.brandId()).isEqualTo(2L);
            assertThat(r.reasons()).containsExactly("BRAND_EXACT:shopkart");
            assertThat(r.decision()).isEqualTo(Decision.REVIEW);
        }

        @Test
        void electraPowerImpersonationIsDetected() {
            MatchResult r = match("electra.bills@ybl", "Bills");
            assertThat(r.brandId()).isEqualTo(3L);
            assertThat(r.reasons()).containsExactly("BRAND_EXACT:electra");
            assertThat(r.decision()).isEqualTo(Decision.REVIEW);
        }
    }

    @Nested
    class Signals {

        @Test
        void exactMatchIgnoresSeparatorsAndCase() {
            MatchResult r = match("Zentra.Bank-Pay@ybl", "Payments");
            assertThat(r.reasons()).containsExactly("BRAND_EXACT:zentrabank");
            assertThat(r.score()).isEqualTo(50);
        }

        @Test
        void exactMatchInDisplayName() {
            MatchResult r = match("rk.payments@ybl", "Shop Kart");
            assertThat(r.reasons()).containsExactly("BRAND_EXACT:shopkart");
        }

        @ParameterizedTest
        @CsvSource({
                "z3ntrabank@ybl, Bank, HOMOGLYPH:zentrabank",
                "sh0pk4rt@ybl, Store, HOMOGLYPH:shopkart",
                "e1ectrapower@ybl, Bills, HOMOGLYPH:electrapower",
                "ab.cd@ybl, Zеntra Bank, HOMOGLYPH:zentrabank",
                "ab.cd@ybl, ЅhорKart, HOMOGLYPH:shopkart",
                "ab.cd@ybl, Ｅｌｅｃｔｒａ, BRAND_EXACT:electra"
        })
        void homoglyphsAreFolded(String vpa, String displayName, String expectedReason) {
            MatchResult r = match(vpa, displayName);
            assertThat(r.reasons()).containsExactly(expectedReason);
        }

        @Test
        void homoglyphScoresSixtyAndIsReview() {
            MatchResult r = match("sh0pkart@ybl", "Store");
            assertThat(r.score()).isEqualTo(60);
            assertThat(r.decision()).isEqualTo(Decision.REVIEW);
        }

        @Test
        void rnAndVvLigaturesAreFolded() {
            Brand medicore = new Brand(7L, "MediCore", Set.of("medicore"), Set.of("medicore@sbi"));
            Brand bluewave = new Brand(11L, "BlueWave", Set.of("bluewave"), Set.of("bluewave@icici"));
            assertThat(service.match("rnedicore@ybl", "x", List.of(medicore)).reasons())
                    .containsExactly("HOMOGLYPH:medicore");
            assertThat(service.match("bluevvave@ybl", "x", List.of(bluewave)).reasons())
                    .containsExactly("HOMOGLYPH:bluewave");
        }

        @ParameterizedTest
        @CsvSource({
                "shopkrat@ybl, TYPOSQUAT:shopkart(distance=1)",      // 8 chars, transposition
                "shpkart@ybl, TYPOSQUAT:shopkart(distance=1)",       // 8 chars, deletion
                "zentrbank@ybl, TYPOSQUAT:zentrabank(distance=1)",   // 10 chars
                "zentrbnak@ybl, TYPOSQUAT:zentrabank(distance=2)",   // 10 chars, 2 edits allowed
                "elektrapower@ybl, TYPOSQUAT:electrapower(distance=1)" // 12 chars
        })
        void typosquatsWithinEditBudgetMatch(String vpa, String expectedReason) {
            MatchResult r = match(vpa, "Store");
            assertThat(r.reasons()).containsExactly(expectedReason);
            assertThat(r.score()).isEqualTo(40);
            assertThat(r.decision()).isEqualTo(Decision.REVIEW);
        }

        @Test
        void typoBudgetDependsOnTokenLength() {
            assertThat(MatchingService.maxEditsFor(5)).isZero();
            assertThat(MatchingService.maxEditsFor(6)).isEqualTo(1);
            assertThat(MatchingService.maxEditsFor(9)).isEqualTo(1);
            assertThat(MatchingService.maxEditsFor(10)).isEqualTo(2);
        }

        @Test
        void sixToNineCharTokensRejectTwoEdits() {
            assertThat(match("shpkrat@ybl", "Store").decision()).isEqualTo(Decision.ALLOW); // 2 edits from shopkart
        }

        @Test
        void tokensUnderSixCharsNeverTypoMatch() {
            Brand zeta = new Brand(9L, "Zeta", Set.of("zetak"), Set.of("zetak@ybl"));
            assertThat(service.match("zetax@ybl", "x", List.of(zeta)).decision()).isEqualTo(Decision.ALLOW);
        }

        @Test
        void typoIsFoundInsideWordWithLureSuffix() {
            MatchResult r = match("zentrbankrefund@ybl", "Desk");
            assertThat(r.reasons()).containsExactly("TYPOSQUAT:zentrabank(distance=1)", "LURE_KEYWORD:refund");
            assertThat(r.score()).isEqualTo(60);
        }
    }

    @Nested
    class LureKeywords {

        @Test
        void lureAddsTwentyWhenBrandMatches() {
            MatchResult r = match("shopkart.cashback@ybl", "Store");
            assertThat(r.score()).isEqualTo(70);
            assertThat(r.reasons()).containsExactly("BRAND_EXACT:shopkart", "LURE_KEYWORD:cashback");
            assertThat(r.decision()).isEqualTo(Decision.BLOCK);
        }

        @Test
        void multipleLureWordsCountOnce() {
            MatchResult r = match("shopkart.refund.helpdesk@ybl", "ShopKart Support");
            assertThat(r.score()).isEqualTo(70);
        }

        @Test
        void lureWithoutBrandSignalDoesNotCount() {
            MatchResult r = match("refund.helpdesk.kyc@ybl", "Customer Support Refund");
            assertThat(r.score()).isZero();
            assertThat(r.brandId()).isNull();
            assertThat(r.reasons()).isEmpty();
            assertThat(r.decision()).isEqualTo(Decision.ALLOW);
        }
    }

    @Nested
    class Selection {

        @Test
        void onlyHighestSignalPerBrandCounts() {
            // display name: exact (50); handle: homoglyph (60) -> 60, not 110
            MatchResult r = match("sh0pkart@ybl", "ShopKart");
            assertThat(r.score()).isEqualTo(60);
            assertThat(r.reasons()).containsExactly("HOMOGLYPH:shopkart");
        }

        @Test
        void highestScoringBrandWins() {
            // ShopKart exact (50) vs Zentra homoglyph (60)
            MatchResult r = match("shopkart.z3ntrabank@ybl", "x");
            assertThat(r.brandId()).isEqualTo(1L);
            assertThat(r.score()).isEqualTo(60);
        }

        @Test
        void tiesGoToLowestBrandId() {
            MatchResult r = match("electra.shopkart@ybl", "x"); // both exact 50
            assertThat(r.brandId()).isEqualTo(2L);
            List<Brand> reversed = new java.util.ArrayList<>(brands);
            java.util.Collections.reverse(reversed);
            MatchResult reversedInput = service.match("electra.shopkart@ybl", "x", reversed);
            assertThat(reversedInput.brandId()).isEqualTo(2L);
        }

        @Test
        void unrelatedPayeeIsAllowed() {
            MatchResult r = match("ramesh.kumar@okicici", "Ramesh Kumar");
            assertThat(r).isEqualTo(new MatchResult(null, null, 0, Decision.ALLOW, List.of()));
        }
    }

    @Nested
    class Thresholds {

        @ParameterizedTest
        @CsvSource({"0, ALLOW", "39, ALLOW", "40, REVIEW", "69, REVIEW", "70, BLOCK", "120, BLOCK"})
        void decisionBoundaries(int score, Decision expected) {
            assertThat(service.decide(score)).isEqualTo(expected);
        }
    }
}
