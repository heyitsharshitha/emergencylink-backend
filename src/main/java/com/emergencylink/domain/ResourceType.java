package com.emergencylink.domain;

public enum ResourceType {
    GENERAL_BED("General beds", "beds"),
    EMERGENCY_BED("Emergency beds", "beds"),
    ICU_BED("ICU beds", "beds"),
    VENTILATOR("Ventilators", "units"),
    BLOOD_UNITS("Blood units", "units"),
    OXYGEN_POINTS("Oxygen supply points", "points"),
    OPERATING_THEATRE("Operating theatres", "theatres"),
    SPECIALIST_ON_CALL("Specialists on call", "doctors");

    private final String label;
    private final String unit;
    ResourceType(String label, String unit) { this.label = label; this.unit = unit; }
    public String label() { return label; }
    public String unit() { return unit; }
}
