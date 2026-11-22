package com.team404.corrosion.profile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The rules for an equipment profile, in plain Java so they are unit-tested without Spring. Returns every problem
 * found, each as a sentence the UI can show as it is; an empty list means the profile is valid.
 *
 * <p>Rules: warning margin &gt; critical margin &gt; 0 (a warning comes before the critical level as the surface
 * approaches the dew point); 0 &lt;= warning RH &lt; critical RH &lt;= 100; surface offset &gt;= 0; minimum temperature
 * &lt; maximum temperature (also a CHECK in V8). Upper bounds keep values inside the DECIMAL columns.
 */
public final class ProfileValidator {

    static final int NAME_MAX = 100;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** dew_margin_*_c are DECIMAL(5,2); a margin this large would never trigger anyway. */
    private static final BigDecimal MARGIN_MAX = new BigDecimal("50");
    /** surface_offset_c is DECIMAL(4,2). */
    private static final BigDecimal OFFSET_MAX = new BigDecimal("20");
    private static final BigDecimal TEMP_MIN = new BigDecimal("-60");
    private static final BigDecimal TEMP_MAX = new BigDecimal("100");

    private ProfileValidator() {
    }

    public static List<String> validate(Profile p) {
        List<String> problems = new ArrayList<>();

        if (p.name() == null || p.name().isBlank()) {
            problems.add("Name must not be empty.");
        } else if (p.name().length() > NAME_MAX) {
            problems.add("Name must be at most " + NAME_MAX + " characters.");
        }

        boolean margins = required(problems, p.marginWarningC(), "marginWarningC")
                & required(problems, p.marginCriticalC(), "marginCriticalC");
        if (margins) {
            if (p.marginCriticalC().compareTo(ZERO) <= 0) {
                problems.add("Critical margin must be greater than 0 °C.");
            }
            if (p.marginWarningC().compareTo(p.marginCriticalC()) <= 0) {
                problems.add("Warning margin must be greater than the critical margin.");
            }
            if (p.marginWarningC().compareTo(MARGIN_MAX) > 0) {
                problems.add("Warning margin must be at most " + MARGIN_MAX + " °C.");
            }
        }

        boolean rh = required(problems, p.rhWarningPct(), "rhWarningPct")
                & required(problems, p.rhCriticalPct(), "rhCriticalPct");
        if (rh) {
            if (p.rhWarningPct().compareTo(ZERO) < 0) {
                problems.add("Warning humidity must be 0 % or more.");
            }
            if (p.rhWarningPct().compareTo(p.rhCriticalPct()) >= 0) {
                problems.add("Warning humidity must be lower than the critical humidity.");
            }
            if (p.rhCriticalPct().compareTo(HUNDRED) > 0) {
                problems.add("Critical humidity must be at most 100 %.");
            }
        }

        if (required(problems, p.surfaceOffsetC(), "surfaceOffsetC")) {
            if (p.surfaceOffsetC().compareTo(ZERO) < 0) {
                problems.add("Surface offset must be 0 °C or more.");
            } else if (p.surfaceOffsetC().compareTo(OFFSET_MAX) > 0) {
                problems.add("Surface offset must be at most " + OFFSET_MAX + " °C.");
            }
        }

        boolean temps = required(problems, p.tempMinC(), "tempMinC") & required(problems, p.tempMaxC(), "tempMaxC");
        if (temps) {
            if (p.tempMinC().compareTo(p.tempMaxC()) >= 0) {
                problems.add("Minimum temperature must be lower than the maximum temperature.");
            }
            if (p.tempMinC().compareTo(TEMP_MIN) < 0 || p.tempMaxC().compareTo(TEMP_MAX) > 0) {
                problems.add("Temperatures must be between " + TEMP_MIN + " and " + TEMP_MAX + " °C.");
            }
        }
        return problems;
    }

    /** Adds "x is required." and returns false when the value is missing. */
    private static boolean required(List<String> problems, BigDecimal value, String field) {
        if (value == null) {
            problems.add(field + " is required.");
            return false;
        }
        return true;
    }
}
