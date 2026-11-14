package com.azentio.doppel;

import java.util.List;
import java.util.Set;

import com.azentio.doppel.brand.Brand;
import com.azentio.doppel.config.DoppelProperties;

/** Shared, Spring-free test data mirroring application.yml and the mandatory brands. */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static DoppelProperties properties() {
        return new DoppelProperties(
                "test-secret",
                new DoppelProperties.Scores(50, 60, 40, 20),
                new DoppelProperties.Thresholds(70, 40),
                List.of("refund", "refunds", "support", "helpdesk", "help", "care", "kyc", "cashback", "offer",
                        "reward", "update"));
    }

    public static Brand zentraBank() {
        return new Brand(1L, "Zentra Bank", Set.of("zentrabank", "zentra"),
                Set.of("zentrabank@zbk", "zentra.support@zbk"));
    }

    public static Brand shopKart() {
        return new Brand(2L, "ShopKart", Set.of("shopkart"), Set.of("shopkart@kotak", "shopkart.refunds@kotak"));
    }

    public static Brand electraPower() {
        return new Brand(3L, "Electra Power", Set.of("electrapower", "electra"), Set.of("electrapower.bills@axis"));
    }

    public static List<Brand> mandatoryBrands() {
        return List.of(zentraBank(), shopKart(), electraPower());
    }
}
