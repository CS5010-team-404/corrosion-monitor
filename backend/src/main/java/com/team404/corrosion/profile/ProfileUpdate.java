package com.team404.corrosion.profile;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Body of PUT /api/v1/profiles/{id}: the same fields as {@link Profile} without the id (an id in the body is ignored;
 * the path decides). name may be left out to keep the current one; every number is required.
 */
public record ProfileUpdate(String name, BigDecimal rhWarningPct, BigDecimal rhCriticalPct, BigDecimal marginWarningC,
                            BigDecimal marginCriticalC, BigDecimal tempMinC, BigDecimal tempMaxC,
                            BigDecimal surfaceOffsetC) {

    /** The profile as it will be stored: name trimmed (or kept), numbers at the columns' two decimal places. */
    Profile applyTo(Profile current) {
        String newName = name == null ? current.name() : name.trim();
        return new Profile(current.id(), newName, scale(rhWarningPct), scale(rhCriticalPct), scale(marginWarningC),
                scale(marginCriticalC), scale(tempMinC), scale(tempMaxC), scale(surfaceOffsetC));
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }
}
