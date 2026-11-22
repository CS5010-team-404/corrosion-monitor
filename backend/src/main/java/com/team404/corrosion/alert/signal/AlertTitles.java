package com.team404.corrosion.alert.signal;

/**
 * Alert titles a person can read at a glance. A condensation alert from analytics names the equipment and its
 * location ("Condensation risk: Rack A1, Server Room A") instead of an id; every other signal keeps the title its
 * producer gave it (or its type when it has none).
 */
public final class AlertTitles {

    static final String ANALYTICS = "ANALYTICS";

    private AlertTitles() {
    }

    /**
     * @param equipmentName the equipment's name, or null if unknown
     * @param locationName  the name of the equipment's location, or null if unknown
     */
    public static String of(Signal signal, String equipmentName, String locationName) {
        String label = analyticsLabel(signal);
        if (label == null) {
            return signal.title() != null ? signal.title() : signal.type();
        }
        return label + ": " + place(signal.equipmentId(), equipmentName, locationName);
    }

    /** True if the title depends on the equipment's name, so the store has to look it up. */
    public static boolean namesEquipment(Signal signal) {
        return analyticsLabel(signal) != null;
    }

    private static String analyticsLabel(Signal signal) {
        if (!ANALYTICS.equals(signal.source()) || signal.type() == null) {
            return null;
        }
        return switch (signal.type()) {
            case "CONDENSATION_RISK" -> "Condensation risk";
            case "CONDENSATION_FORECAST" -> "Condensation forecast";
            default -> null;
        };
    }

    private static String place(Long equipmentId, String equipmentName, String locationName) {
        if (isBlank(equipmentName)) {
            return equipmentId == null ? "unknown equipment" : "equipment " + equipmentId;
        }
        return isBlank(locationName) ? equipmentName : equipmentName + ", " + locationName;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
