package com.azentio.doppel.matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.azentio.doppel.brand.Brand;
import com.azentio.doppel.config.DoppelProperties;

/**
 * Scores a payee (VPA + display name) against the brand registry.
 *
 * <ol>
 *   <li>Official VPA of any brand → ALLOW, score 0.</li>
 *   <li>For every brand, the strongest of BRAND_EXACT / HOMOGLYPH / TYPOSQUAT found in the VPA handle
 *       (part before '@') or the display name. Only one brand signal counts per brand.</li>
 *   <li>The brand with the highest score wins; ties go to the lowest brand id.</li>
 *   <li>LURE_KEYWORD adds its score once, and only when a brand signal matched.</li>
 *   <li>Score is mapped to BLOCK / REVIEW / ALLOW using the configured thresholds.</li>
 * </ol>
 */
@Service
public class MatchingService {

    public static final String OFFICIAL_VPA = "OFFICIAL_VPA";
    public static final String BRAND_EXACT = "BRAND_EXACT";
    public static final String HOMOGLYPH = "HOMOGLYPH";
    public static final String TYPOSQUAT = "TYPOSQUAT";
    public static final String LURE_KEYWORD = "LURE_KEYWORD";

    private final DoppelProperties properties;
    private final List<String> lureKeywords;

    public MatchingService(DoppelProperties properties) {
        this.properties = properties;
        // Longest first, so stripping "helpdesk" happens before "help".
        this.lureKeywords = properties.lureKeywords().stream()
                .map(TextNormalizer::skeleton)
                .filter(k -> !k.isEmpty())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    public MatchResult match(String vpa, String displayName, List<Brand> brands) {
        List<Brand> ordered = brands.stream().sorted(Comparator.comparing(Brand::getId)).toList();

        String normalizedVpa = TextNormalizer.normalize(vpa);
        for (Brand brand : ordered) {
            for (String official : brand.getOfficialVpas()) {
                if (TextNormalizer.normalize(official).equals(normalizedVpa)) {
                    return new MatchResult(brand.getId(), brand.getName(), 0, Decision.ALLOW, List.of(OFFICIAL_VPA));
                }
            }
        }

        int at = normalizedVpa.lastIndexOf('@');
        String handle = at >= 0 ? normalizedVpa.substring(0, at) : normalizedVpa;
        List<String> texts = List.of(handle, displayName == null ? "" : displayName);

        // normalise each text once per request (not once per brand)
        List<TextForms> forms = texts.stream()
                .map(t -> new TextForms(TextNormalizer.compact(t), TextNormalizer.skeleton(t), typoCandidates(t)))
                .toList();

        Brand bestBrand = null;
        Signal bestSignal = null;
        for (Brand brand : ordered) {
            Signal signal = bestSignalFor(brand, forms);
            // strictly greater → on ties the earlier (lower id) brand is kept
            if (signal != null && (bestSignal == null || signal.score() > bestSignal.score())) {
                bestBrand = brand;
                bestSignal = signal;
            }
        }

        if (bestBrand == null) {
            return new MatchResult(null, null, 0, Decision.ALLOW, List.of());
        }

        List<String> reasons = new ArrayList<>();
        reasons.add(bestSignal.reason());
        int score = bestSignal.score();

        Set<String> lures = findLureKeywords(texts);
        if (!lures.isEmpty()) {
            score += properties.scores().lureKeyword();
            lures.forEach(k -> reasons.add(LURE_KEYWORD + ":" + k));
        }

        return new MatchResult(bestBrand.getId(), bestBrand.getName(), score, decide(score), reasons);
    }

    public Decision decide(int score) {
        if (score >= properties.thresholds().block()) {
            return Decision.BLOCK;
        }
        if (score >= properties.thresholds().review()) {
            return Decision.REVIEW;
        }
        return Decision.ALLOW;
    }

    /** Highest-scoring brand signal for one brand across all texts and tokens, or null. */
    private Signal bestSignalFor(Brand brand, List<TextForms> forms) {
        Signal best = null;
        List<String> tokens = sortedTokens(brand);
        List<String> tokenSkeletons = tokens.stream().map(TextNormalizer::skeleton).toList();
        for (TextForms form : forms) {
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                String tokenSkeleton = tokenSkeletons.get(i);
                Signal signal = null;
                if (form.raw().contains(token)) {
                    signal = new Signal(properties.scores().brandExact(), BRAND_EXACT + ":" + token);
                } else if (form.skeleton().contains(tokenSkeleton)) {
                    signal = new Signal(properties.scores().homoglyph(), HOMOGLYPH + ":" + token);
                } else {
                    int distance = typoDistance(tokenSkeleton, form.words());
                    if (distance > 0) {
                        signal = new Signal(properties.scores().typosquat(),
                                TYPOSQUAT + ":" + token + "(distance=" + distance + ")");
                    }
                }
                if (signal != null && (best == null || signal.score() > best.score())) {
                    best = signal;
                }
            }
        }
        return best;
    }

    /** Normalized tokens, longest first, so the most specific token is reported on equal scores. */
    private static List<String> sortedTokens(Brand brand) {
        return brand.getTokens().stream()
                .map(TextNormalizer::compact)
                .filter(t -> !t.isEmpty())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    /**
     * Smallest OSA distance (1..maxEdits) between the token and any candidate word, or 0 if none qualifies.
     * Tokens shorter than 6 chars never typo-match; 6–9 chars allow 1 edit; 10+ chars allow 2 edits.
     */
    static int typoDistance(String token, List<String> words) {
        int maxEdits = maxEditsFor(token.length());
        if (maxEdits == 0) {
            return 0;
        }
        int best = 0;
        for (String word : words) {
            if (Math.abs(word.length() - token.length()) > maxEdits) {
                continue;
            }
            int d = OsaDistance.between(word, token);
            if (d >= 1 && d <= maxEdits && (best == 0 || d < best)) {
                best = d;
            }
        }
        return best;
    }

    static int maxEditsFor(int tokenLength) {
        if (tokenLength < 6) {
            return 0;
        }
        return tokenLength <= 9 ? 1 : 2;
    }

    /** Skeleton words, plus each word with lure keywords removed (so "zentrbankrefund" yields "zentrbank"). */
    private List<String> typoCandidates(String text) {
        Set<String> candidates = new LinkedHashSet<>();
        for (String word : TextNormalizer.skeletonWords(text)) {
            candidates.add(word);
            String stripped = word;
            for (String lure : lureKeywords) {
                stripped = stripped.replace(lure, "");
            }
            if (!stripped.isEmpty()) {
                candidates.add(stripped);
            }
        }
        return new ArrayList<>(candidates);
    }

    private Set<String> findLureKeywords(List<String> texts) {
        Set<String> found = new LinkedHashSet<>();
        for (String text : texts) {
            String skeleton = TextNormalizer.skeleton(text);
            for (String lure : lureKeywords) {
                if (skeleton.contains(lure)) {
                    found.add(lure);
                }
            }
        }
        return found;
    }

    private record Signal(int score, String reason) {
    }

    /** Exact form, folded form and typo-candidate words of one examined text. */
    private record TextForms(String raw, String skeleton, List<String> words) {
    }
}
