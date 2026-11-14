package com.azentio.doppel.matching;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Normalization helpers used by {@link MatchingService}.
 * <ul>
 *   <li>{@link #normalize}: NFKC + lowercase.</li>
 *   <li>{@link #compact}: normalize, then drop every non letter/digit (used for exact matching).</li>
 *   <li>{@link #skeleton}: compact + confusable folding (Cyrillic/Greek look-alikes, digits, rn→m, vv→w).</li>
 * </ul>
 */
public final class TextNormalizer {

    /**
     * Upper-case Cyrillic/Greek look-alikes, folded BEFORE lower-casing (e.g. Greek 'Ν' looks like 'N',
     * but its lower-case form 'ν' looks like 'v').
     */
    private static final Map<Character, Character> UPPER_CONFUSABLES = Map.ofEntries(
            // Cyrillic
            Map.entry('А', 'a'), Map.entry('В', 'b'), Map.entry('Е', 'e'), Map.entry('К', 'k'),
            Map.entry('М', 'm'), Map.entry('Н', 'h'), Map.entry('О', 'o'), Map.entry('Р', 'p'),
            Map.entry('С', 'c'), Map.entry('Т', 't'), Map.entry('Х', 'x'), Map.entry('Ѕ', 's'),
            Map.entry('І', 'i'), Map.entry('Ј', 'j'), Map.entry('У', 'y'),
            // Greek
            Map.entry('Α', 'a'), Map.entry('Β', 'b'), Map.entry('Ε', 'e'), Map.entry('Ζ', 'z'),
            Map.entry('Η', 'h'), Map.entry('Ι', 'i'), Map.entry('Κ', 'k'), Map.entry('Μ', 'm'),
            Map.entry('Ν', 'n'), Map.entry('Ο', 'o'), Map.entry('Ρ', 'p'), Map.entry('Τ', 't'),
            Map.entry('Υ', 'y'), Map.entry('Χ', 'x'));

    /** Lower-case Cyrillic and Greek characters and digits that render like Latin letters. */
    private static final Map<Character, Character> CONFUSABLES = Map.ofEntries(
            // Cyrillic
            Map.entry('а', 'a'), Map.entry('в', 'b'), Map.entry('е', 'e'), Map.entry('ё', 'e'),
            Map.entry('һ', 'h'), Map.entry('і', 'i'), Map.entry('ї', 'i'), Map.entry('ј', 'j'),
            Map.entry('к', 'k'), Map.entry('м', 'm'), Map.entry('н', 'h'), Map.entry('о', 'o'),
            Map.entry('р', 'p'), Map.entry('с', 'c'), Map.entry('т', 't'), Map.entry('у', 'y'),
            Map.entry('х', 'x'), Map.entry('ѕ', 's'), Map.entry('ԁ', 'd'), Map.entry('ԛ', 'q'),
            Map.entry('ԝ', 'w'), Map.entry('ӏ', 'l'),
            // Greek
            Map.entry('α', 'a'), Map.entry('β', 'b'), Map.entry('ε', 'e'), Map.entry('η', 'n'),
            Map.entry('ι', 'i'), Map.entry('κ', 'k'), Map.entry('ν', 'v'), Map.entry('ο', 'o'),
            Map.entry('ρ', 'p'), Map.entry('τ', 't'), Map.entry('υ', 'u'), Map.entry('χ', 'x'),
            Map.entry('γ', 'y'), Map.entry('ω', 'w'), Map.entry('ζ', 'z'),
            // Digits
            Map.entry('0', 'o'), Map.entry('1', 'l'), Map.entry('3', 'e'), Map.entry('4', 'a'),
            Map.entry('5', 's'), Map.entry('7', 't'), Map.entry('8', 'b'), Map.entry('9', 'g'));

    private TextNormalizer() {
    }

    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        return Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
    }

    public static String compact(String input) {
        return normalize(input).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    public static String skeleton(String input) {
        return foldConfusables(input).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** Words split on separators; each word is folded to its skeleton. */
    public static List<String> skeletonWords(String input) {
        return Arrays.stream(foldConfusables(input).split("[^\\p{L}\\p{N}]+"))
                .filter(w -> !w.isEmpty())
                .toList();
    }

    /** NFKC → fold upper-case look-alikes → lowercase → fold lower-case look-alikes/digits → rn→m, vv→w. */
    private static String foldConfusables(String input) {
        if (input == null) {
            return "";
        }
        String nfkc = Normalizer.normalize(input, Normalizer.Form.NFKC);
        StringBuilder upperFolded = new StringBuilder(nfkc.length());
        for (char c : nfkc.toCharArray()) {
            upperFolded.append(UPPER_CONFUSABLES.getOrDefault(c, c));
        }
        String lower = upperFolded.toString().toLowerCase(Locale.ROOT).trim();
        StringBuilder sb = new StringBuilder(lower.length());
        for (char c : lower.toCharArray()) {
            sb.append(CONFUSABLES.getOrDefault(c, c));
        }
        return sb.toString().replace("rn", "m").replace("vv", "w");
    }
}
