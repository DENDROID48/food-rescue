package com.foodrescue.domain;

/**
 * Milestones a customer unlocks by collecting rescued food. Each one watches one figure of the
 * customer's history ({@link Metric}) and unlocks when that figure reaches its target.
 */
public enum Achievement {
    FIRST_RESCUE("First rescue", "Collect your first rescued food", Metric.RESCUES, 1),
    REGULAR("Regular rescuer", "Collect rescued food 10 times", Metric.RESCUES, 10),
    HERO("Rescue hero", "Collect rescued food 50 times", Metric.RESCUES, 50),
    FIVE_KG("5 kg saved from the bin", "Rescue 5 kg of food", Metric.GRAMS, 5_000),
    TWENTY_FIVE_KG("25 kg saved from the bin", "Rescue 25 kg of food", Metric.GRAMS, 25_000),
    SMART_SAVER("Smart saver", "Save 100 lei compared with full prices", Metric.LEI_SAVED, 100),
    LOCAL_EXPLORER("Local explorer", "Collect from 3 different shops", Metric.SHOPS, 3),
    DROP_CATCHER("Drop catcher", "Collect a Rescue Drop", Metric.DROPS, 1),
    STEADY_STREAK("Steady streak", "Rescue food 4 weeks in a row", Metric.BEST_WEEK_STREAK, 4);

    /** The figure of a customer's history that an achievement watches. */
    public enum Metric { RESCUES, GRAMS, LEI_SAVED, SHOPS, DROPS, BEST_WEEK_STREAK }

    private final String title;
    private final String description;
    private final Metric metric;
    private final long target;

    Achievement(String title, String description, Metric metric, long target) {
        this.title = title;
        this.description = description;
        this.metric = metric;
        this.target = target;
    }

    public String title() { return title; }
    public String description() { return description; }
    public Metric metric() { return metric; }
    public long target() { return target; }
}
