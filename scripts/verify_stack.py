"""Proves Phase 1's acceptance criteria against the running stack.

Each check asks the real services, the way a person would look: a purchase through the gateway, live
traffic in Prometheus, a trace that crosses at least four services in Tempo, logs from every service
in Loki, and every query of the Grafana dashboard returning data through Grafana itself. Standard library
only; it runs in a container on the stack's network (`make verify`). Prints what it measured and exits
non-zero on the first criterion that does not hold.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

GATEWAY = os.environ.get("GATEWAY_URL", "http://gateway:8080")
PROMETHEUS = os.environ.get("PROMETHEUS_URL", "http://prometheus:9090")
TEMPO = os.environ.get("TEMPO_URL", "http://tempo:3200")
LOKI = os.environ.get("LOKI_URL", "http://loki:3100")
GRAFANA = os.environ.get("GRAFANA_URL", "http://grafana:3000")
SERVICES = {"gateway", "catalog", "cart", "checkout", "payments", "inventory", "notifier"}
DEADLINE_SECONDS = int(os.environ.get("VERIFY_TIMEOUT_SECONDS", "240"))


class CheckFailed(Exception):
    """Raised when a criterion does not hold before its deadline.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """


def request(method: str, url: str, body: dict | None = None, accept: str = "application/json") -> tuple[int, str]:
    """Sends one HTTP request and returns the status and body, without raising on error statuses.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Accept": accept}
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def get_json(url: str, **params) -> dict:
    """Gets a URL with query parameters and decodes its JSON, failing on any error status.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    query = f"?{urllib.parse.urlencode(params)}" if params else ""
    status, body = request("GET", url + query)
    if status != 200:
        raise CheckFailed(f"GET {url} answered {status}: {body[:200]}")
    return json.loads(body)


def eventually(name: str, check):
    """Retries a check until it returns a truthy value or the deadline passes, then reports it.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    started = time.monotonic()
    last_error = "no result"
    while time.monotonic() - started < DEADLINE_SECONDS:
        try:
            result = check()
            if result:
                print(f"ok    {name}: {result}")
                return result
            last_error = "condition not met yet"
        except (CheckFailed, urllib.error.URLError, OSError, ValueError, KeyError, IndexError) as error:
            last_error = str(error)
        time.sleep(3)
    raise CheckFailed(f"{name}: {last_error}")


def ready() -> str:
    """Every observability backend answers its readiness endpoint.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    for name, url in (("loki", f"{LOKI}/ready"), ("tempo", f"{TEMPO}/ready"), ("prometheus", f"{PROMETHEUS}/-/ready"),
                      ("grafana", f"{GRAFANA}/api/health")):
        status, body = request("GET", url, accept="*/*")
        if status != 200:
            raise CheckFailed(f"{name} is not ready: {status} {body[:80]}")
    return "loki, tempo, prometheus and grafana are ready"


def purchase() -> str:
    """A shopper buys through the gateway: stock goes down by what was bought.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    sku = "SKU-0007"
    status, body = request("GET", f"{GATEWAY}/api/products/{sku}")
    if status != 200:
        raise CheckFailed(f"product lookup answered {status}")
    status, body = request("POST", f"{GATEWAY}/api/carts")
    if status != 201:
        raise CheckFailed(f"cart creation answered {status}")
    cart = json.loads(body)["id"]
    status, body = request("PUT", f"{GATEWAY}/api/carts/{cart}/items", {"sku": sku, "quantity": 2})
    if status != 200:
        raise CheckFailed(f"adding to the cart answered {status}: {body[:200]}")
    status, body = request("POST", f"{GATEWAY}/api/checkouts", {"cartId": cart, "paymentToken": "tok_visa"})
    if status != 201:
        raise CheckFailed(f"checkout answered {status}: {body[:200]}")
    order = json.loads(body)
    status, _ = request("POST", f"{GATEWAY}/api/checkouts", {"cartId": cart, "paymentToken": "tok_visa"})
    if status != 404:
        raise CheckFailed(f"checking out the same cart twice answered {status}, expected 404 for a cleared cart")
    return f"order {order['id']} placed for {order['totalCents']} {order['currency']}, and its cart cleared"


def prometheus_value(expr: str) -> float:
    """Evaluates an instant PromQL query and returns its single value, or 0 when it is empty.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    result = get_json(f"{PROMETHEUS}/api/v1/query", query=expr)["data"]["result"]
    return float(result[0]["value"][1]) if result else 0.0


def live_traffic() -> str:
    """Prometheus sees shoppers at the gateway, orders placed and confirmations sent.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    rate = prometheus_value('sum(rate(http_server_request_duration_seconds_count{service_name="gateway", http_route=~"/api/.*"}[1m]))')
    orders = prometheus_value('sum(mercadillo_checkouts_total{outcome="placed"})')
    sent = prometheus_value('sum(mercadillo_notifications_total{outcome="sent"})')
    if rate <= 0 or orders <= 0 or sent <= 0:
        return ""
    return f"{rate:.2f} shopper requests/s at the gateway, {orders:.0f} orders placed, {sent:.0f} confirmations sent"


def trace_across_services() -> str:
    """Tempo holds a successful checkout trace that crosses at least four services.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    found = get_json(f"{TEMPO}/api/search", limit="20",
                     q='{ resource.service.name = "gateway" && span.http.route = "/api/checkouts" '
                       "&& span.http.response.status_code = 201 }")
    best = (0, "", [])
    for summary in found.get("traces", []):
        trace = get_json(f"{TEMPO}/api/v2/traces/{summary['traceID']}")
        services = set()
        for resource_spans in trace.get("trace", trace)["resourceSpans"]:
            for attribute in resource_spans["resource"]["attributes"]:
                if attribute["key"] == "service.name":
                    services.add(attribute["value"]["stringValue"])
        if len(services) > best[0]:
            best = (len(services), summary["traceID"], sorted(services))
    if best[0] < 4:
        return ""
    return f"trace {best[1]} crosses {best[0]} services: {', '.join(best[2])}"


def logs_from_every_service() -> str:
    """Loki holds logs from all seven services, and a checkout log line carries its trace ID.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    names = set(get_json(f"{LOKI}/loki/api/v1/label/service_name/values")["data"])
    missing = SERVICES - names
    if missing:
        raise CheckFailed(f"no logs yet from {', '.join(sorted(missing))}")
    lines = get_json(f"{LOKI}/loki/api/v1/query_range", query='{service_name="checkout"} |= "placed"',
                     limit="1")["data"]["result"]
    if not lines or not lines[0]["stream"].get("trace_id"):
        raise CheckFailed("checkout logs carry no trace_id")
    return f"logs from all {len(SERVICES)} services; checkout lines carry trace_id"


def dashboard_shows_traffic() -> str:
    """Every Prometheus query on the overview dashboard returns data when Grafana runs it.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    dashboard = get_json(f"{GRAFANA}/api/dashboards/uid/mercadillo-overview")["dashboard"]
    queries = [(panel["title"], target["expr"]) for panel in dashboard["panels"]
               for target in panel.get("targets", []) if target.get("datasource", {}).get("uid") == "prometheus"]
    if len(queries) < 10:
        raise CheckFailed(f"the dashboard has only {len(queries)} Prometheus queries")
    now = int(time.time() * 1000)
    empty = []
    for title, expr in queries:
        status, body = request("POST", f"{GRAFANA}/api/ds/query", {
            "from": str(now - 10 * 60 * 1000), "to": str(now),
            "queries": [{"refId": "A", "datasource": {"type": "prometheus", "uid": "prometheus"},
                         "expr": expr.replace("$__rate_interval", "1m"), "intervalMs": 15000, "maxDataPoints": 100}],
        })
        if status != 200:
            raise CheckFailed(f"Grafana could not run the query of '{title}': {status} {body[:200]}")
        frames = json.loads(body)["results"]["A"].get("frames", [])
        has_values = any(frame.get("data", {}).get("values") and len(frame["data"]["values"][-1]) > 0 for frame in frames)
        if not has_values:
            empty.append(title)
    if empty:
        raise CheckFailed(f"panels with no data: {', '.join(empty)}")
    graph = prometheus_value("sum(traces_service_graph_request_total)")
    if graph <= 0:
        raise CheckFailed("the service graph has no edges yet")
    return f"all {len(queries)} Prometheus queries return data through Grafana, and the service graph has edges"


def main() -> int:
    """Runs every check in order and stops at the first that fails.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    try:
        eventually("backends ready", ready)
        eventually("purchase through the gateway", purchase)
        eventually("live traffic in Prometheus", live_traffic)
        eventually("a trace crosses at least four services", trace_across_services)
        eventually("logs from every service", logs_from_every_service)
        eventually("the dashboard shows live traffic", dashboard_shows_traffic)
    except CheckFailed as failure:
        print(f"FAIL  {failure}")
        return 1
    print("verify: every Phase 1 criterion holds")
    return 0


if __name__ == "__main__":
    sys.exit(main())
