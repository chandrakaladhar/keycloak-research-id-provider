package au.edu.sydney.research.keycloak.researchid;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResearchIdTest {

    /** Always picks the given number. */
    private static RandomGenerator fixed(int value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int nextInt(int bound) {
                return value;
            }
        };
    }

    @Test
    void checkCharacterMatchesOrcidsPublishedExamples() {
        // ORCID uses the same ISO 7064 MOD 11-2 check: 0000-0002-1825-0097 and 0000-0002-1694-233X.
        assertThat(ResearchId.checkCharacter("000000021825009")).isEqualTo('7');
        assertThat(ResearchId.checkCharacter("000000021694233")).isEqualTo('x');
    }

    @Test
    void generatesTheStandardForm() {
        String value = ResearchId.generate(fixed(4_821_093)).value();

        assertThat(value).matches("rc-4821-093[0-9x]");
        assertThat(value.charAt(11)).isEqualTo(ResearchId.checkCharacter("4821093"));
        assertThat(ResearchId.isCanonical(value)).isTrue();
    }

    @Test
    void keepsLeadingZeros() {
        String value = ResearchId.generate(fixed(42)).value();

        assertThat(value).startsWith("rc-0000-042");
        assertThat(ResearchId.isCanonical(value)).isTrue();
    }

    @Test
    void canProduceAnXCheckCharacterInLowerCase() {
        // Find a 7-digit number whose check character is 10, i.e. 'x'.
        int withX = 0;
        while (ResearchId.checkCharacter(String.format("%07d", withX)) != 'x') {
            withX++;
        }
        String value = ResearchId.generate(fixed(withX)).value();

        assertThat(value).endsWith("x");
        assertThat(ResearchId.parse(value.toUpperCase())).map(ResearchId::value).hasValue(value);
    }

    @Test
    void randomIdsAreAlwaysValid() {
        SplittableRandom random = new SplittableRandom(7);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String value = ResearchId.generate(random).value();
            assertThat(ResearchId.isCanonical(value)).as(value).isTrue();
            seen.add(value);
        }
        assertThat(seen).hasSizeGreaterThan(9_900);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RC 4821 093", "rc_4821_093", "rc4821093", " Rc-4821-093 "})
    void readsWhatPeopleType(String typedWithoutCheck) {
        String canonical = ResearchId.generate(fixed(4_821_093)).value();
        String typed = typedWithoutCheck.strip() + canonical.charAt(11);

        assertThat(ResearchId.parse(typed)).map(ResearchId::value).hasValue(canonical);
    }

    @Test
    void rejectsAWrongCheckCharacter() {
        String canonical = ResearchId.generate(fixed(4_821_093)).value();
        char wrong = canonical.charAt(11) == '0' ? '1' : '0';
        String mistyped = canonical.substring(0, 11) + wrong;

        assertThat(ResearchId.parse(mistyped)).isEmpty();
        assertThat(ResearchId.isCanonical(mistyped)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "4821-0937", "rc-4821-093", "rc-4821-09377", "xx-4821-0937",
            "rc-48a1-0937", "rc-4821-093y"})
    void rejectsMalformedInput(String input) {
        assertThat(ResearchId.parse(input)).isEmpty();
        assertThat(ResearchId.isCanonical(input)).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(ResearchId.parse(null)).isEmpty();
        assertThat(ResearchId.isCanonical(null)).isFalse();
    }

    @Test
    void isCanonicalOnlyForTheStoredForm() {
        String canonical = ResearchId.generate(fixed(4_821_093)).value();

        assertThat(ResearchId.isCanonical(canonical)).isTrue();
        assertThat(ResearchId.isCanonical(canonical.toUpperCase())).isFalse();
        assertThat(ResearchId.isCanonical(canonical.replace("-", ""))).isFalse();
    }
}
