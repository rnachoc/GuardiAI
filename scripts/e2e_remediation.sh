#!/bin/sh
# Remediation end to end on the running stack: inventory is killed, a restart is proposed, the policy
# engine sends it for approval, a person approves, the executor restarts the real container through the
# Docker API and verifies recovery against Prometheus, and the audit chain stays intact.
#
# Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
set -eu

POLICY=${POLICY_URL:-http://127.0.0.1:8090}
ID="prop-$(cat /proc/sys/kernel/random/uuid 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')"

# Posts JSON to the policy service and prints the answer.
# Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
post() {
  curl --silent --show-error --fail --max-time 300 -H 'Content-Type: application/json' -d "$2" "$POLICY$1"
}

echo "kill inventory"
docker compose kill inventory > /dev/null
test "$(docker compose ps inventory --format '{{.State}}')" != "running"

echo "propose restart_service on inventory"
post /proposals "{\"id\":\"$ID\",\"action\":\"restart_service\",\"target\":\"inventory\"}" | grep -q '"verdict":"NEEDS_APPROVAL"'

echo "execute before approval: refused"
post "/proposals/$ID/execute" '{}' | grep -q '"outcome":"REFUSED"'
test "$(docker compose ps inventory --format '{{.State}}')" != "running"

echo "approve, then execute: the executor restarts inventory and verifies recovery"
post "/proposals/$ID/approvals" '{"by":"on-call"}' | grep -q '"approvals":1'
outcome=$(post "/proposals/$ID/execute" '{}')
echo "  $outcome"
echo "$outcome" | grep -q '"outcome":"VERIFIED"'
test "$(docker compose ps inventory --format '{{.State}}')" = "running"

echo "execute again: nothing runs twice"
post "/proposals/$ID/execute" '{}' | grep -q '"outcome":"ALREADY_DONE"'

echo "the timeline holds the whole story, in order, with times"
curl --silent --fail "$POLICY/audit?limit=200" | python3 -c '
import json, sys
proposal = sys.argv[1]
mine = [e for e in json.load(sys.stdin) if proposal in (e["data"].get("id"), e["data"].get("proposal"))]
kinds = [e["kind"] for e in mine]
assert kinds == ["proposal", "decision", "execution", "approval", "claim", "execution", "verification"], kinds
assert all(e.get("at") for e in mine), "an entry has no time"
' "$ID"

echo "audit chain intact"
curl --silent --fail "$POLICY/audit/verify" | grep -q '"problems":\[\]'
echo "e2e: remediation verified end to end"
