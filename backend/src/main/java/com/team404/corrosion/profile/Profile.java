package com.team404.corrosion.profile;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One equipment profile (FR8) with the field names of {@code Profile} in frontend/src/api/types.ts. Stored in
 * equipment_profile (V1, V3, V8): rh_warn_pct, rh_critical_pct, dew_margin_warn_c, dew_margin_critical_c, temp_min_c,
 * temp_max_c, surface_offset_c. Numbers are BigDecimal so the DECIMAL columns round-trip exactly.
 */
public record Profile(long id, String name, BigDecimal rhWarningPct, BigDecimal rhCriticalPct,
                      BigDecimal marginWarningC, BigDecimal marginCriticalC, BigDecimal tempMinC, BigDecimal tempMaxC,
                      BigDecimal surfaceOffsetC) {

    /** The editable values, in a stable order, for the audit event's old/new details. */
    public Map<String, Object> values() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("rhWarningPct", rhWarningPct);
        m.put("rhCriticalPct", rhCriticalPct);
        m.put("marginWarningC", marginWarningC);
        m.put("marginCriticalC", marginCriticalC);
        m.put("tempMinC", tempMinC);
        m.put("tempMaxC", tempMaxC);
        m.put("surfaceOffsetC", surfaceOffsetC);
        return m;
    }
}
