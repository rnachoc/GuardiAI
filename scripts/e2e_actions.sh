#!/bin/sh
# The other real actions end to end on the running stack: payments fails over to its standby through
# Toxiproxy, the catalog's cache is cleared in Redis, the notifier's consumer is paused and resumed, and
# inventory gains a replica.
# Each is proposed, approved and executed through the policy service, which verifies it against
# Prometheus. Catalog limits allow one failover an hour, so run this at most once an hour.
#
# Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
set -eu

POLICY=${POLICY_URL:-http://127.0.0.1:8090}

# Posts JSON to the policy service and prints the answer.
# Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
post() {
  curl --silent --show-error --fail --max-time 400 -H 'Content-Type: application/json' -d "$2" "$POLICY$1"
}

# Proposes an action, approves it as two people, executes it, and fails unless the outcome is VERIFIED.
# Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
remediate() {
  id="prop-$(cat /proc/sys/kernel/random/uuid 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')"
  post /proposals "{\"id\":\"$id\",\"action\":\"$1\",\"target\":\"$2\"}" | grep -q '"verdict":"NEEDS_APPROVAL"'
  post "/proposals/$id/approvals" '{"by":"on-call"}' > /dev/null
  post "/proposals/$id/approvals" '{"by":"incident-lead"}' > /dev/null
  outcome=$(post "/proposals/$id/execute" '{}')
  echo "  $1 on $2: $outcome"
  echo "$outcome" | grep -q '"outcome":"VERIFIED"'
}

# Reads checkout's proxy to payments from Toxiproxy, inside the Compose network.
# Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
upstream() {
  docker compose exec -T policy wget -qO- http://toxiproxy:8474/proxies/checkout-payments | sed 's/.*"upstream":"\([^"]*\)".*/\1/'
}

echo "failover: checkout's payments traffic moves to the standby"
test "$(upstream)" = "payments:8080"
remediate failover payments
test "$(upstream)" = "payments-standby:8080"
served=$(curl --silent --get --data-urlencode 'query=sum(rate(http_server_request_duration_seconds_count{service_name="payments-standby"}[1m]))' \
  http://127.0.0.1:9090/api/v1/query | sed 's/.*"value":\[[^,]*,"\([^"]*\)".*/\1/')
echo "  the standby serves $served charges per second"
python3 -c "import sys; sys.exit(0 if float(sys.argv[1]) > 0 else 1)" "$served"
echo "  failing back by hand, so the next run starts from the primary"
docker compose exec -T policy wget -qO- --post-data '{"upstream":"payments:8080"}' http://toxiproxy:8474/proxies/checkout-payments > /dev/null
test "$(upstream)" = "payments:8080"

echo "clear_cache: the catalog's cached products are deleted in Redis"
remediate clear_cache catalog

echo "pause_consumer, then resume_consumer: the notifier stops, then catches up"
remediate pause_consumer notifier
test "$(docker compose ps notifier --format '{{.State}}')" = "paused"
remediate resume_consumer notifier
test "$(docker compose ps notifier --format '{{.State}}')" = "running"

echo "scale_deployment: inventory gains a replica, which reports as a second instance"
remediate scale_deployment inventory
test "$(docker ps --filter label=io.guardia.replica-of=inventory --format '{{.State}}')" = "running"
echo "  removing the replica by hand, so the next run starts from one"
docker rm -f "$(docker ps --quiet --filter label=io.guardia.replica-of=inventory)" > /dev/null

echo "audit chain intact"
curl --silent --fail "$POLICY/audit/verify" | grep -q '"problems":\[\]'
echo "e2e: failover, cache clearing, consumer pause and scale-out verified end to end"
