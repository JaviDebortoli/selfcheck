-- History of measurements, states and alerts. Time columns hold mission time in milliseconds (spec section 5).

CREATE TABLE measurement (
    mission_time_ms  BIGINT           NOT NULL,
    subject_id       TEXT             NOT NULL,
    variable         TEXT             NOT NULL,
    value            DOUBLE PRECISION NOT NULL,
    unit             TEXT             NOT NULL,
    quality          TEXT             NOT NULL,
    activity_context TEXT,
    fasting          BOOLEAN,
    rhythm           TEXT,
    alarm_signs      TEXT
);
SELECT create_hypertable('measurement', by_range('mission_time_ms', 86400000));
CREATE INDEX measurement_subject_variable_time ON measurement (subject_id, variable, mission_time_ms DESC);

CREATE TABLE variable_state (
    mission_time_ms BIGINT  NOT NULL,
    subject_id      TEXT    NOT NULL,
    variable        TEXT    NOT NULL,
    state           TEXT,
    to_confirm      BOOLEAN NOT NULL,
    reason_code     TEXT    NOT NULL,
    reason_params   JSONB   NOT NULL
);
SELECT create_hypertable('variable_state', by_range('mission_time_ms', 86400000));

CREATE TABLE summary_state (
    mission_time_ms  BIGINT NOT NULL,
    subject_id       TEXT   NOT NULL,
    measurement_group TEXT,
    state            TEXT   NOT NULL,
    trigger_variable TEXT
);
SELECT create_hypertable('summary_state', by_range('mission_time_ms', 86400000));

CREATE TABLE alert (
    id              BIGSERIAL,
    mission_time_ms BIGINT NOT NULL,
    subject_id      TEXT   NOT NULL,
    variable        TEXT,
    state           TEXT   NOT NULL,
    code            TEXT   NOT NULL,
    params          JSONB  NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX alert_time ON alert (mission_time_ms DESC);
