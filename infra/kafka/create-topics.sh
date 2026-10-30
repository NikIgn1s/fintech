#!/bin/sh
set -e
BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}"
PARTITIONS="${TOPIC_PARTITIONS:-3}"
TOPICS="transfers.check transfers.check.result transfers.status-changed transactions.imported transactions.categorized budget.alerts transfers.check.DLT transfers.check.result.DLT transactions.imported.DLT transactions.categorized.DLT"
for topic in $TOPICS; do
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$topic" --partitions "$PARTITIONS" --replication-factor 1
done
/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --list
