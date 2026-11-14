package io.selfcheck.contracts;

/**
 * Kafka topic names shared by every service.
 *
 * <p>Topics are created by the {@code kafka-init} service in {@code docker-compose.yml}; keep both lists in sync.
 */
public final class Topics {

    /** {@link MissionProfile}, keyed by {@link #MISSION_KEY}. Compacted. */
    public static final String MISSION_PROFILE = "selfcheck.mission-profile";

    /** {@link MedicalHistory}, keyed by crew member id. Compacted. */
    public static final String MEDICAL_HISTORY = "selfcheck.medical-history";

    /** {@link Measurement}, keyed by subject id (crew member id or {@link #HABITAT_ID}). */
    public static final String MEASUREMENTS = "selfcheck.measurements";

    /** {@link VariableState}, keyed by subject id. */
    public static final String VARIABLE_STATES = "selfcheck.variable-states";

    /** {@link SummaryState} of groups, crew members and habitat, keyed by subject id. */
    public static final String SUMMARY_STATES = "selfcheck.summary-states";

    /** {@link Alert}, keyed by subject id. */
    public static final String ALERTS = "selfcheck.alerts";

    /** {@link MissionControl} commands from the simulator's control API, keyed by {@link #MISSION_KEY}. */
    public static final String CONTROL = "selfcheck.control";

    /** Record key of the single mission profile. */
    public static final String MISSION_KEY = "mission";

    /** Subject id of the shared habitat. */
    public static final String HABITAT_ID = "habitat";

    private Topics() {
    }
}
