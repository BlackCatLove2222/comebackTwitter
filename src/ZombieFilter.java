package app.morphe.extension.newx.timeline;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Pure heuristics for the JP zombie (impression-farmer) reply filter.
 *
 * <p>Modeled on the rule set of the x-zombie-killer browser extension:
 * local, deterministic checks over the post text and simple structural
 * signals. No network, no account lists, no automatic blocking. Every
 * method here is static and side-effect free so the rules can be unit
 * tested without the timeline machinery.
 */
final class ZombieFilter {

    static final String REASON_CHARSET = "CHARSET";
    static final String REASON_EMOJI_ONLY = "EMOJI_ONLY";
    static final String REASON_DUPLICATE = "DUPLICATE";
    static final String REASON_NEAR_DUPLICATE = "NEAR_DUPLICATE";
    static final String REASON_COPY_LINK_OR_QUOTE = "COPY_LINK_OR_QUOTE";
    static final String REASON_REPEAT_SCATTER = "REPEAT_SCATTER";
    static final String REASON_QUOTED_TEMPLATE = "QUOTED_TEMPLATE";
    static final String REASON_QUOTE_BAIT = "QUOTE_BAIT";

    /** Z10: a quote reply whose own text is at most this long is bait. */
    static final int QUOTE_BAIT_MAX_TEXT_LENGTH = 20;
    /** Z4b: minimum similarity-form length before a near-duplicate verdict. */
    static final int NEAR_DUPLICATE_MIN_LENGTH = 10;
    /** Z4b: character-trigram Dice coefficient at which two texts are one template. */
    private static final double NEAR_DUPLICATE_DICE_THRESHOLD = 0.5;
    /** Z9: shingle overlap at which two quoted texts count as one template. */
    private static final double TEMPLATE_SHINGLE_THRESHOLD = 0.5;

    /** Minimum normalized source length before a copy verdict is allowed. */
    static final int COPY_MIN_SOURCE_LENGTH = 12;
    /** Shingle size for the copy overlap computation. */
    private static final int COPY_SHINGLE_LENGTH = 8;
    /** Fraction of source shingles that must appear in the reply. */
    private static final double COPY_SHINGLE_THRESHOLD = 0.7;

    private ZombieFilter() {
    }

    /**
     * Canonical form used by the duplicate and copy checks: NFKC,
     * lowercased, URLs and leading mention tokens removed, and all
     * whitespace dropped so spacing tricks do not defeat matching.
     */
    static String normalizeText(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(normalized.length());
        boolean inUrl = false;
        boolean inMention = false;
        for (int index = 0; index < normalized.length(); index++) {
            char c = normalized.charAt(index);
            if (Character.isWhitespace(c)) {
                inUrl = false;
                inMention = false;
                continue;
            }
            if (inUrl) continue;
            if (inMention) {
                if (isMentionChar(c)) continue;
                inMention = false;
            }
            if (c == 'h' && normalized.startsWith("http", index)) {
                inUrl = true;
                continue;
            }
            if (c == '@' && (index == 0 || !isMentionChar(normalized.charAt(index - 1)))) {
                inMention = true;
                continue;
            }
            result.append(c);
        }
        return result.toString();
    }

    private static boolean isMentionChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Z4b similarity form: the canonical text reduced to letters and
     * digits only. Template farms decorate the same sentence with
     * different emoji, punctuation and self-mentions; dropping everything
     * that is not a letter or digit leaves the sentence itself. Capped
     * so pathological inputs stay cheap to compare.
     */
    static String normalizeForSimilarity(String text) {
        String normalized = normalizeText(text);
        StringBuilder result = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char c = normalized.charAt(index);
            if (Character.isLetterOrDigit(c)) result.append(c);
        }
        return result.length() > 200 ? result.substring(0, 200) : result.toString();
    }

    /**
     * Z4b: two similarity-form texts are the same template when they are
     * equal, or when their character-trigram Dice coefficient reaches
     * {@link #NEAR_DUPLICATE_DICE_THRESHOLD}. Trigrams tolerate the small
     * edits farms make (an emoji moved, a word swapped for a synonym)
     * while the length floor keeps short stock phrases from colliding.
     * Both inputs must already be in similarity form.
     */
    static boolean isNearDuplicate(String similarityA, String similarityB) {
        if (similarityA == null || similarityB == null) return false;
        if (similarityA.length() < NEAR_DUPLICATE_MIN_LENGTH
                || similarityB.length() < NEAR_DUPLICATE_MIN_LENGTH) {
            return false;
        }
        if (similarityA.equals(similarityB)) return true;
        return trigramDice(similarityA, similarityB) >= NEAR_DUPLICATE_DICE_THRESHOLD;
    }

    /** Dice coefficient over character-trigram multisets. */
    private static double trigramDice(String a, String b) {
        if (a.length() < 3 || b.length() < 3) return 0;
        java.util.Map<String, Integer> gramsA = trigramCounts(a);
        java.util.Map<String, Integer> gramsB = trigramCounts(b);
        int shared = 0;
        for (java.util.Map.Entry<String, Integer> entry : gramsA.entrySet()) {
            Integer other = gramsB.get(entry.getKey());
            if (other != null) shared += Math.min(entry.getValue(), other);
        }
        int total = (a.length() - 2) + (b.length() - 2);
        return total == 0 ? 0 : (2.0 * shared) / total;
    }

    private static java.util.Map<String, Integer> trigramCounts(String text) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (int index = 0; index + 3 <= text.length(); index++) {
            counts.merge(text.substring(index, index + 3), 1, Integer::sum);
        }
        return counts;
    }

    /** Z1: text contains code points from a blocked script range. */
    static boolean containsBlockedScript(String text, boolean arabic, boolean devanagari) {
        if (text == null) return false;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (arabic && isArabic(codePoint)) return true;
            if (devanagari && codePoint >= 0x0900 && codePoint <= 0x097F) return true;
        }
        return false;
    }

    private static boolean isArabic(int codePoint) {
        return codePoint >= 0x0600 && codePoint <= 0x06FF
                || codePoint >= 0x0750 && codePoint <= 0x077F
                || codePoint >= 0xFB50 && codePoint <= 0xFDFF
                || codePoint >= 0xFE70 && codePoint <= 0xFEFF;
    }

    /**
     * Z2: after removing URLs, mentions and whitespace, something remains
     * but it contains no letter or digit at all (emoji / symbols only).
     */
    static boolean isEmojiOnly(String text) {
        if (text == null) return false;
        String stripped = stripUrlsAndMentions(text);
        boolean anyContent = false;
        for (int index = 0; index < stripped.length(); ) {
            int codePoint = stripped.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) continue;
            anyContent = true;
            if (Character.isLetterOrDigit(codePoint)) return false;
        }
        return anyContent;
    }

    private static String stripUrlsAndMentions(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder(normalized.length());
        boolean inUrl = false;
        boolean inMention = false;
        for (int index = 0; index < normalized.length(); index++) {
            char c = normalized.charAt(index);
            if (Character.isWhitespace(c)) {
                inUrl = false;
                inMention = false;
                result.append(c);
                continue;
            }
            if (inUrl) continue;
            if (inMention) {
                if (isMentionChar(c)) continue;
                inMention = false;
            }
            if (c == 'h' && normalized.startsWith("http", index)) {
                inUrl = true;
                continue;
            }
            if (c == '@') {
                inMention = true;
                continue;
            }
            result.append(c);
        }
        return result.toString();
    }

    /**
     * Z7 similarity: the reply copies the source post's wording. True when
     * the normalized reply contains the normalized source verbatim, or when
     * at least {@link #COPY_SHINGLE_THRESHOLD} of the source's character
     * shingles appear in the reply. Short sources require containment so a
     * brief quoted phrase alone cannot trigger the rule.
     */
    static boolean isCopyOf(String normalizedSource, String normalizedReply) {
        if (normalizedSource == null || normalizedReply == null) return false;
        if (normalizedSource.length() < COPY_MIN_SOURCE_LENGTH) return false;
        if (normalizedReply.contains(normalizedSource)) return true;
        // The source was copied with edits or reordering.
        if (shingleHitRate(normalizedSource, normalizedReply) >= COPY_SHINGLE_THRESHOLD) {
            return true;
        }
        // The reply is itself mostly a verbatim chunk of the source (for
        // example only the second half), plus a small addition such as a
        // link caption. Require a substantial reply so a short quoted
        // phrase with original commentary stays legitimate.
        return normalizedReply.length() >= 20
                && shingleHitRate(normalizedReply, normalizedSource) >= COPY_SHINGLE_THRESHOLD;
    }

    /**
     * Z9: two quoted texts are the same spam template when either one's
     * shingles mostly appear in the other, or they share one long verbatim
     * chunk. Template farms vary a phrase or two per account but reuse the
     * hook lines verbatim, so a shared chunk of 12+ characters covering a
     * good part of the shorter text is treated as the same template.
     */
    static boolean isTemplateSimilar(String normalizedA, String normalizedB) {
        if (normalizedA == null || normalizedB == null) return false;
        if (normalizedA.length() < COPY_MIN_SOURCE_LENGTH
                || normalizedB.length() < COPY_MIN_SOURCE_LENGTH) {
            return false;
        }
        if (normalizedA.equals(normalizedB)) return true;
        if (shingleHitRate(normalizedA, normalizedB) >= TEMPLATE_SHINGLE_THRESHOLD
                || shingleHitRate(normalizedB, normalizedA) >= TEMPLATE_SHINGLE_THRESHOLD) {
            return true;
        }
        int longest = longestCommonSubstringLength(normalizedA, normalizedB);
        int shorter = Math.min(normalizedA.length(), normalizedB.length());
        return longest >= 12 && longest * 20 >= shorter * 7;
    }

    /** Length of the longest common substring; inputs are capped for cost. */
    private static int longestCommonSubstringLength(String a, String b) {
        String first = a.length() > 400 ? a.substring(0, 400) : a;
        String second = b.length() > 400 ? b.substring(0, 400) : b;
        int[] previous = new int[second.length() + 1];
        int best = 0;
        for (int i = 1; i <= first.length(); i++) {
            int[] current = new int[second.length() + 1];
            for (int j = 1; j <= second.length(); j++) {
                if (first.charAt(i - 1) == second.charAt(j - 1)) {
                    current[j] = previous[j - 1] + 1;
                    if (current[j] > best) best = current[j];
                }
            }
            previous = current;
        }
        return best;
    }

    /** Fraction of {@code text}'s shingles that appear in {@code haystack}. */
    private static double shingleHitRate(String text, String haystack) {
        if (text.length() < COPY_SHINGLE_LENGTH) return 0;
        int shingleCount = text.length() - COPY_SHINGLE_LENGTH + 1;
        int hits = 0;
        for (int index = 0; index < shingleCount; index++) {
            String shingle = text.substring(index, index + COPY_SHINGLE_LENGTH);
            if (haystack.contains(shingle)) hits++;
        }
        return shingleCount == 0 ? 0 : (double) hits / shingleCount;
    }
}
