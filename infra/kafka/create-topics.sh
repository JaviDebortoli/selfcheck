#!/bin/sh
# Creates every SelfCheck topic. Keep in sync with contracts/src/main/java/io/selfcheck/contracts/Topics.java.
set -e

BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-kafka:29092}"

create() {
  topic="$1"
  shift
  kafka-topics --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$topic" --partitions 1 --replication-factor 1 "$@"
}

create selfcheck.mission-profile --config cleanup.policy=compact
create selfcheck.medical-history --config cleanup.policy=compact
create selfcheck.measurements
create selfcheck.variable-states
create selfcheck.summary-states
create selfcheck.alerts

kafka-topics --bootstrap-server "$BOOTSTRAP" --list
