package com.team404.corrosion.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfileValidatorTest {

    /** The demo IT profile from V3/V8: valid. */
    static Profile valid() {
        return new Profile(1, "IT equipment (ASHRAE)", d("60"), d("70"), d("3"), d("1"), d("18"), d("27"), d("1.5"));
    }

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static List<String> problems(Profile p) {
        return ProfileValidator.validate(p);
    }

    private static Profile with(String field, BigDecimal v) {
        Profile p = valid();
        return new Profile(p.id(), p.name(),
                field.equals("rhW") ? v : p.rhWarningPct(), field.equals("rhC") ? v : p.rhCriticalPct(),
                field.equals("mW") ? v : p.marginWarningC(), field.equals("mC") ? v : p.marginCriticalC(),
                field.equals("tMin") ? v : p.tempMinC(), field.equals("tMax") ? v : p.tempMaxC(),
                field.equals("off") ? v : p.surfaceOffsetC());
    }

    @Test
    void demoProfileIsValid() {
        assertThat(problems(valid())).isEmpty();
    }

    @Test
    void warningMarginMustBeAboveCriticalMargin() {
        assertThat(problems(with("mW", d("1")))).containsExactly("Warning margin must be greater than the critical margin.");
        assertThat(problems(with("mW", d("0.5")))).contains("Warning margin must be greater than the critical margin.");
    }

    @Test
    void criticalMarginMustBePositive() {
        assertThat(problems(with("mC", d("0")))).containsExactly("Critical margin must be greater than 0 °C.");
        assertThat(problems(with("mC", d("-1")))).containsExactly("Critical margin must be greater than 0 °C.");
    }

    @Test
    void warningHumidityMustBeBelowCriticalHumidity() {
        assertThat(problems(with("rhW", d("70"))))
                .containsExactly("Warning humidity must be lower than the critical humidity.");
        assertThat(problems(with("rhW", d("-1")))).containsExactly("Warning humidity must be 0 % or more.");
    }

    @Test
    void criticalHumidityMayBeHundredButNotMore() {
        assertThat(problems(with("rhC", d("100")))).isEmpty();
        assertThat(problems(with("rhC", d("100.01")))).containsExactly("Critical humidity must be at most 100 %.");
    }

    @Test
    void surfaceOffsetMayBeZeroButNotNegative() {
        assertThat(problems(with("off", d("0")))).isEmpty();
        assertThat(problems(with("off", d("-0.1")))).containsExactly("Surface offset must be 0 °C or more.");
        assertThat(problems(with("off", d("25")))).containsExactly("Surface offset must be at most 20 °C.");
    }

    @Test
    void temperatureRangeMustBeOrdered() {
        assertThat(problems(with("tMin", d("27"))))
                .containsExactly("Minimum temperature must be lower than the maximum temperature.");
        assertThat(problems(with("tMax", d("500")))).containsExactly("Temperatures must be between -60 and 100 °C.");
    }

    @Test
    void missingNumbersAreNamed() {
        assertThat(problems(with("mC", null))).containsExactly("marginCriticalC is required.");
        assertThat(problems(with("off", null))).containsExactly("surfaceOffsetC is required.");
    }

    @Test
    void nameMustBePresentAndShort() {
        Profile p = valid();
        Profile blank = new Profile(1, "  ", p.rhWarningPct(), p.rhCriticalPct(), p.marginWarningC(),
                p.marginCriticalC(), p.tempMinC(), p.tempMaxC(), p.surfaceOffsetC());
        Profile tooLong = new Profile(1, "x".repeat(101), p.rhWarningPct(), p.rhCriticalPct(), p.marginWarningC(),
                p.marginCriticalC(), p.tempMinC(), p.tempMaxC(), p.surfaceOffsetC());
        assertThat(problems(blank)).containsExactly("Name must not be empty.");
        assertThat(problems(tooLong)).containsExactly("Name must be at most 100 characters.");
    }

    @Test
    void reportsEveryProblemAtOnce() {
        Profile p = new Profile(1, "x", d("80"), d("70"), d("1"), d("2"), d("30"), d("20"), d("-1"));
        assertThat(problems(p)).hasSize(4);
    }

    @Test
    void updateKeepsNameWhenOmittedAndRoundsToTwoDecimals() {
        ProfileUpdate u = new ProfileUpdate(null, d("61.234"), d("70"), d("3"), d("1"), d("18"), d("27"), d("1.5"));
        Profile next = u.applyTo(valid());
        assertThat(next.name()).isEqualTo("IT equipment (ASHRAE)");
        assertThat(next.rhWarningPct()).isEqualByComparingTo("61.23");
        assertThat(next.rhWarningPct().scale()).isEqualTo(2);
        assertThat(new ProfileUpdate("  New name ", d("60"), d("70"), d("3"), d("1"), d("18"), d("27"), d("1.5"))
                .applyTo(valid()).name()).isEqualTo("New name");
    }
}
