package com.emergencylink.domain;

public enum Urgency {
    CRITICAL(0), URGENT(1), STANDARD(2);
    private final int rank;
    Urgency(int rank) { this.rank = rank; }
    public int rank() { return rank; }
}
