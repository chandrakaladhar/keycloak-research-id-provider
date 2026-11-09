package au.edu.sydney.research.keycloak.researchid;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * A person's research id, for example {@code rc-4821-0937}.
 *
 * <p>Seven random digits plus one check character, grouped in fours after the {@code rc-} prefix,
 * always lower case. The check character uses ISO 7064 MOD 11-2 (as ORCID does), so it is a digit
 * or {@code x}. The id carries no personal information and never changes.
 */
public final class ResearchId {

    /** Number of random digits; gives 10 million possible ids. */
    static final int RANDOM_DIGITS = 7;

    private static final int ID_SPACE = 10_000_000;
    private static final Pattern CANONICAL = Pattern.compile("rc-[0-9]{4}-[0-9]{3}[0-9x]");
    private static final Pattern COMPACT = Pattern.compile("rc[0-9]{7}[0-9x]");
    private static final Pattern IGNORED = Pattern.compile("[\\s_-]");

    private final String value;

    private ResearchId(String value) {
        this.value = value;
    }

    /** A new random research id. Uniqueness is the caller's job (see {@link ResearchIdMinter}). */
    public static ResearchId generate(RandomGenerator random) {
        return fromDigits(String.format("%07d", random.nextInt(ID_SPACE)));
    }

    /**
     * Reads a research id as a person might type it: case, spaces, underscores and missing hyphens
     * are ignored, but the {@code rc} prefix and a correct check character are required. {@code RC
     * 48210937}, {@code rc_4821_0937} and {@code rc48210937} all read as {@code rc-4821-0937}.
     */
    public static Optional<ResearchId> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String compact = IGNORED.matcher(input.toLowerCase(Locale.ROOT)).replaceAll("");
        if (!COMPACT.matcher(compact).matches()) {
            return Optional.empty();
        }
        String digits = compact.substring(2, 2 + RANDOM_DIGITS);
        if (checkCharacter(digits) != compact.charAt(2 + RANDOM_DIGITS)) {
            return Optional.empty();
        }
        return Optional.of(fromDigits(digits));
    }

    /** True if {@code value} is already in the stored form, {@code rc-dddd-dddc}, with a valid check. */
    public static boolean isCanonical(String value) {
        return value != null
                && CANONICAL.matcher(value).matches()
                && parse(value).map(id -> id.value.equals(value)).orElse(false);
    }

    /** ISO 7064 MOD 11-2 check character for a string of digits: {@code 0}–{@code 9} or {@code x}. */
    static char checkCharacter(String digits) {
        int total = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(i) - '0';
            if (digit < 0 || digit > 9) {
                throw new IllegalArgumentException("Not a digit string: " + digits);
            }
            total = (total + digit) * 2 % 11;
        }
        int result = (12 - total) % 11;
        return result == 10 ? 'x' : (char) ('0' + result);
    }

    private static ResearchId fromDigits(String digits) {
        String all = digits + checkCharacter(digits);
        return new ResearchId("rc-" + all.substring(0, 4) + "-" + all.substring(4));
    }

    /** The stored form, for example {@code rc-4821-0937}. */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ResearchId id && id.value.equals(value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
