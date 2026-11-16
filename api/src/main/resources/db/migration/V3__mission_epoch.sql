-- Mission epoch the stored history belongs to (see Topics.EPOCH_HEADER). Survives api restarts.
CREATE TABLE mission_epoch (
    id    INT    PRIMARY KEY,
    epoch BIGINT NOT NULL
);
