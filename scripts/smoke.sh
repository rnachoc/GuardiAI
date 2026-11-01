#!/bin/sh
# Proves that each backing service of the running stack does its job: the database answers a
# query, the cache stores and returns a value, and the broker carries a message from a producer
# to a consumer. A healthcheck alone only shows that a process is up.
set -eu

compose() { docker compose "$@"; }

echo "postgres: run a query"
# The variables expand inside the container, which holds the credentials, so the quotes are single.
# shellcheck disable=SC2016
answer=$(compose exec -T postgres sh -c 'psql --no-psqlrc --tuples-only --no-align --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --command "select 40 + 2"')
[ "$answer" = "42" ] || { echo "postgres answered '$answer' instead of 42" >&2; exit 1; }

echo "redis: store a value and read it back"
token="smoke-$(date +%s)"
compose exec -T redis redis-cli set guardia:smoke "$token" EX 60 > /dev/null
answer=$(compose exec -T redis redis-cli get guardia:smoke)
[ "$answer" = "$token" ] || { echo "redis returned '$answer' instead of '$token'" >&2; exit 1; }

echo "kafka: produce a message and consume it"
topic="guardia.smoke.$(date +%s)"
kafka_bin=/opt/kafka/bin
compose exec -T kafka "$kafka_bin/kafka-topics.sh" --bootstrap-server kafka:9092 \
  --create --topic "$topic" --partitions 1 --replication-factor 1 > /dev/null
echo "$token" | compose exec -T kafka "$kafka_bin/kafka-console-producer.sh" \
  --bootstrap-server kafka:9092 --topic "$topic" > /dev/null
answer=$(compose exec -T kafka "$kafka_bin/kafka-console-consumer.sh" --bootstrap-server kafka:9092 \
  --topic "$topic" --from-beginning --max-messages 1 --timeout-ms 20000 2> /dev/null)
compose exec -T kafka "$kafka_bin/kafka-topics.sh" --bootstrap-server kafka:9092 \
  --delete --topic "$topic" > /dev/null
[ "$answer" = "$token" ] || { echo "kafka delivered '$answer' instead of '$token'" >&2; exit 1; }

echo "smoke: every backing service works"
