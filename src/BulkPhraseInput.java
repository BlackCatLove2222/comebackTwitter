package app.morphe.extension.newx.postfilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * JP jp24.13.21: bulk word input for the filter settings word editors.
 *
 * <p>The word editors' add box used to take its whole input as ONE
 * phrase, so pasting a prepared word list (one word per line, or a
 * comma-separated list) registered a single useless mega-phrase.
 * {@link #split} breaks the input into individual words first: tokens
 * are separated by commas (ASCII {@code ,}, ideographic {@code 、},
 * fullwidth {@code ，}) and by line breaks, each token is trimmed
 * (including full-width spaces), empty tokens are skipped, and
 * duplicates within the input are dropped, preserving first-seen
 * order. Duplicates against the already-registered words are the
 * stores' business (their add methods reject them, and the editors
 * skip rejected tokens when bulk-adding).
 *
 * <p>Documented trade-off: separators ALWAYS split. A phrase that
 * legitimately contains a comma can no longer be entered through the
 * add box — it can still arrive via a filter-bundle import, whose
 * format is a structured list and never passes through here.
 */
public final class BulkPhraseInput {

    private BulkPhraseInput() {
    }

    /** Splits raw add-box input into the words it contains. */
    public static List<String> split(String raw) {
        LinkedHashSet<String> words = new LinkedHashSet<>();
        if (raw != null) {
            for (String token : raw.split("[,、，\\r\\n]+", -1)) {
                String word = trim(token);
                if (!word.isEmpty()) {
                    words.add(word);
                }
            }
        }
        return new ArrayList<>(words);
    }

    private static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isSpace(char c) {
        // Character.isWhitespace covers the ideographic space U+3000 as
        // well; the explicit comparison keeps that obvious at the call
        // sites this class was written for (JP IME input).
        return c == '　' || Character.isWhitespace(c);
    }
}
