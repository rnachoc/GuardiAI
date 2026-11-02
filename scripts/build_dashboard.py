"""Generates the Mercadillo overview dashboard for Grafana.

The dashboard is written as code so that its queries can be reviewed and tested; the JSON it produces is
committed, and a test fails if the two drift apart. Run: python3 scripts/build_dashboard.py

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import json
import sys
from pathlib import Path

OUTPUT = Path(__file__).resolve().parent.parent / "deploy/observability/grafana/dashboards/mercadillo-overview.json"
PROM = {"type": "prometheus", "uid": "prometheus"}
LOKI = {"type": "loki", "uid": "loki"}
TEMPO = {"type": "tempo", "uid": "tempo"}

OUTCOME_COLORS = {
    "placed": "#3DD68C",
    "declined": "#FFB547",
    "out_of_stock": "#FF8A5C",
    "dependency_failed": "#FF5C5C",
    "failed": "#FF5C5C",
    "unknown_cart": "#8E8E93",
    "unknown_product": "#8E8E93",
    "empty_cart": "#8E8E93",
    "mixed_currencies": "#8E8E93",
}
SHOPPER_ROUTES = 'service_name="gateway", http_route=~"/api/.*"'
BUSINESS_ROUTES = 'service_namespace="mercadillo", http_route!~"/actuator.*"'


class Layout:
    """Places panels on Grafana's 24-column grid, left to right and top to bottom.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """

    def __init__(self):
        """Starts at the top-left corner with no panels.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        self.x = 0
        self.y = 0
        self.row_height = 0
        self.panels = []

    def add(self, panel: dict, width: int, height: int) -> None:
        """Adds a panel, wrapping to a new line when the current one is full.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        if self.x + width > 24:
            self.x = 0
            self.y += self.row_height
            self.row_height = 0
        panel["id"] = len(self.panels) + 1
        panel["gridPos"] = {"x": self.x, "y": self.y, "w": width, "h": height}
        self.panels.append(panel)
        self.x += width
        self.row_height = max(self.row_height, height)

    def section(self, title: str) -> None:
        """Starts a titled section on a new line.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        if self.x:
            self.x = 0
            self.y += self.row_height
        self.row_height = 0
        self.add({"type": "row", "title": title, "collapsed": False, "panels": []}, 24, 1)
        self.x = 0
        self.y += 1
        self.row_height = 0


def target(expr: str, legend: str = "", ref: str = "A", instant: bool = False) -> dict:
    """Builds a Prometheus query for a panel.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    return {"datasource": PROM, "refId": ref, "expr": expr, "legendFormat": legend or "__auto",
            "range": not instant, "instant": instant}


def stat(title: str, description: str, expr: str, unit: str, color: str, decimals: int = 1,
         thresholds: list | None = None) -> dict:
    """Builds a single-number panel with a faint sparkline.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    steps = thresholds or [{"color": color, "value": None}]
    return {
        "type": "stat",
        "title": title,
        "description": description,
        "datasource": PROM,
        "targets": [target(expr)],
        "fieldConfig": {
            "defaults": {
                "unit": unit,
                "decimals": decimals,
                "color": {"mode": "thresholds"},
                "thresholds": {"mode": "absolute", "steps": steps},
            },
            "overrides": [],
        },
        "options": {
            "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
            "colorMode": "value",
            "graphMode": "area",
            "justifyMode": "center",
            "textMode": "value",
            "wideLayout": True,
            "showPercentChange": False,
            "text": {"valueSize": 38},
        },
    }


def series(title: str, description: str, targets: list, unit: str, stacked: bool = False,
           bars: bool = False, decimals: int | None = None, colors: dict | None = None,
           soft_max: float | None = None) -> dict:
    """Builds a time series panel with thin lines, a soft fill and a table legend.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    defaults = {
        "unit": unit,
        "color": {"mode": "palette-classic"},
        "custom": {
            "drawStyle": "bars" if bars else "line",
            "lineWidth": 1 if bars else 2,
            "lineInterpolation": "smooth",
            "fillOpacity": 85 if bars else 12,
            "gradientMode": "opacity",
            "showPoints": "never",
            "spanNulls": True,
            "axisBorderShow": False,
            "axisPlacement": "auto",
            "stacking": {"mode": "normal" if stacked else "none", "group": "A"},
            "thresholdsStyle": {"mode": "off"},
        },
    }
    if decimals is not None:
        defaults["decimals"] = decimals
    if soft_max is not None:
        defaults["min"] = 0
        defaults["custom"]["axisSoftMax"] = soft_max
    overrides = [
        {"matcher": {"id": "byName", "options": name},
         "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": color}}]}
        for name, color in (colors or {}).items()
    ]
    return {
        "type": "timeseries",
        "title": title,
        "description": description,
        "datasource": PROM,
        "targets": targets,
        "fieldConfig": {"defaults": defaults, "overrides": overrides},
        "options": {
            "legend": {"displayMode": "table", "placement": "right", "calcs": ["mean", "max"], "showLegend": True},
            "tooltip": {"mode": "multi", "sort": "desc"},
        },
    }


def build() -> dict:
    """Builds the whole dashboard.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    grid = Layout()
    grid.section("At a glance")
    grid.add(stat("Shopper requests", "Requests per second reaching the gateway's public API.",
                  f"sum(rate(http_server_request_duration_seconds_count{{{SHOPPER_ROUTES}}}[$__rate_interval]))",
                  "reqps", "#5E9EFF"), 4, 5)
    grid.add(stat("Server errors", "Share of public API requests answered with a 5xx status.",
                  f'sum(rate(http_server_request_duration_seconds_count{{{SHOPPER_ROUTES}, http_response_status_code=~"5.."}}[$__rate_interval])) '
                  f"/ sum(rate(http_server_request_duration_seconds_count{{{SHOPPER_ROUTES}}}[$__rate_interval])) or vector(0)",
                  "percentunit", "#3DD68C", 2,
                  [{"color": "#3DD68C", "value": None}, {"color": "#FFB547", "value": 0.01}, {"color": "#FF5C5C", "value": 0.05}]), 4, 5)
    grid.add(stat("p95 latency", "95th percentile of public API latency, measured at the gateway.",
                  f"histogram_quantile(0.95, sum by (le) (rate(http_server_request_duration_seconds_bucket{{{SHOPPER_ROUTES}}}[$__rate_interval]))) * 1000",
                  "ms", "#B48CFF", 0,
                  [{"color": "#B48CFF", "value": None}, {"color": "#FFB547", "value": 500}, {"color": "#FF5C5C", "value": 1000}]), 4, 5)
    grid.add(stat("Orders placed", "Orders placed per minute.",
                  'sum(rate(mercadillo_checkouts_total{outcome="placed"}[$__rate_interval])) * 60',
                  "none", "#3DD68C"), 4, 5)
    grid.add(stat("Payments declined", "Checkouts declined by the payment provider, per minute.",
                  'sum(rate(mercadillo_checkouts_total{outcome="declined"}[$__rate_interval])) * 60 or vector(0)',
                  "none", "#FFB547"), 4, 5)
    grid.add(stat("Notifier lag", "Order events waiting for the notifier, measured by the broker.",
                  'sum(kafka_consumergroup_lag{consumergroup="notifier"}) or vector(0)', "none", "#3DD68C", 0,
                  [{"color": "#3DD68C", "value": None}, {"color": "#FFB547", "value": 50}, {"color": "#FF5C5C", "value": 500}]), 4, 5)

    grid.section("Services")
    grid.add(series("Requests by service", "Requests per second each service serves, health checks excluded.",
                    [target(f"sum by (service_name) (rate(http_server_request_duration_seconds_count{{{BUSINESS_ROUTES}}}[$__rate_interval]))",
                            "{{service_name}}")], "reqps"), 12, 8)
    grid.add(series("p95 latency by service", "95th percentile latency of each service's own endpoints.",
                    [target(f"histogram_quantile(0.95, sum by (service_name, le) (rate(http_server_request_duration_seconds_bucket{{{BUSINESS_ROUTES}}}[$__rate_interval])))",
                            "{{service_name}}")], "s"), 12, 8)
    grid.add(series("Server errors by service", "5xx answers per second, by service.",
                    [target(f'sum by (service_name) (rate(http_server_request_duration_seconds_count{{{BUSINESS_ROUTES}, http_response_status_code=~"5.."}}[$__rate_interval])) '
                            'or label_replace(vector(0), "service_name", "all services", "", "")',
                            "{{service_name}}")], "reqps", soft_max=1), 12, 8)
    grid.add(series("Checkouts by outcome", "How checkouts end, per minute.",
                    [target("sum by (outcome) (rate(mercadillo_checkouts_total[$__rate_interval])) * 60", "{{outcome}}")],
                    "none", stacked=True, bars=True, decimals=1, colors=OUTCOME_COLORS), 12, 8)

    grid.section("Dependencies")
    grid.add({
        "type": "nodeGraph",
        "title": "Service graph",
        "description": "Who calls whom, derived by Tempo from the traces.",
        "datasource": TEMPO,
        "targets": [{"datasource": TEMPO, "refId": "A", "queryType": "serviceMap"}],
        "options": {},
    }, 24, 14)
    grid.add(series("Database connections in use", "Share of each service's connection pool in use.",
                    [target('sum by (service_name) (db_client_connections_usage{state="used"}) '
                            "/ sum by (service_name) (db_client_connections_max)", "{{service_name}}")],
                    "percentunit", soft_max=1), 12, 7)
    grid.add(series("Kafka consumer lag", "Order events the notifier has not processed yet, measured by the broker.",
                    [target('sum by (topic) (kafka_consumergroup_lag{consumergroup="notifier"})', "{{topic}}")],
                    "none", decimals=0, soft_max=10), 12, 7)

    grid.section("Runtime")
    grid.add(series("JVM heap in use", "Heap memory each service is using.",
                    [target('sum by (service_name) (jvm_memory_used_bytes{jvm_memory_type="heap", service_namespace="mercadillo"})',
                            "{{service_name}}")], "bytes"), 12, 7)
    grid.add(series("CPU", "Recent CPU use of each service's JVM, as a share of the CPUs it can use.",
                    [target('avg by (service_name) (jvm_cpu_recent_utilization_ratio{service_namespace="mercadillo"})',
                            "{{service_name}}")], "percentunit"), 12, 7)

    grid.section("GuardiAI")
    grid.add(stat("Proposals judged", "Remediation proposals the policy engine judged in the time range, counted from its audit log.",
                  "sum(increase(guardia_proposals_total[$__range])) or vector(0)", "none", "#5E9EFF", 0), 4, 5)
    grid.add(stat("Unsafe proposals", "Proposals the engine rejected in the time range: outside the catalog, a target not allowed, too wide a blast radius or a failed precondition.",
                  'sum(increase(guardia_decisions_total{verdict="REJECTED"}[$__range])) or vector(0)', "none", "#FFB547", 0), 4, 5)
    grid.add(stat("Verified fixes", "Actions after which Prometheus confirmed recovery, in the time range.",
                  'sum(increase(guardia_verifications_total{outcome="verified"}[$__range])) or vector(0)', "none", "#3DD68C", 0), 4, 5)
    grid.add(stat("Reverts", "Actions reverted because recovery was not confirmed, in the time range.",
                  "sum(increase(guardia_reverts_total[$__range])) or vector(0)", "none", "#3DD68C", 0,
                  [{"color": "#3DD68C", "value": None}, {"color": "#FFB547", "value": 1}]), 4, 5)
    grid.add(stat("Escalations", "Times a person was called because a remediation failed, in the time range.",
                  "sum(increase(guardia_escalations_total[$__range])) or vector(0)", "none", "#3DD68C", 0,
                  [{"color": "#3DD68C", "value": None}, {"color": "#FF5C5C", "value": 1}]), 4, 5)
    grid.add(stat("Audit chain", "1 when every audit entry is hash-linked to the one before it; 0 means the log was tampered with.",
                  "min(guardia_audit_chain_intact)", "none", "#3DD68C", 0,
                  [{"color": "#FF5C5C", "value": None}, {"color": "#3DD68C", "value": 1}]), 4, 5)

    grid.section("GuardiAI agents")
    runs = "sum(max_over_time(guardia_investigations_total[$__range]))"
    grid.add(stat("Investigations", "Investigations that sent telemetry in the time range (OpenTelemetry GenAI conventions).",
                  runs, "none", "#5E9EFF", 0), 5, 5)
    grid.add(stat("Evidence validity", "Share of investigations whose every cited piece of evidence was re-run and gave the same answer.",
                  f'sum(max_over_time(guardia_investigations_total{{guardia_evidence_valid="true"}}[$__range])) / {runs}', "percentunit", "#3DD68C", 0,
                  [{"color": "#FF5C5C", "value": None}, {"color": "#FFB547", "value": 0.9}, {"color": "#3DD68C", "value": 1}]), 5, 5)
    grid.add(stat("Steps per investigation", "Mean number of model turns before a diagnosis.",
                  "sum(max_over_time(guardia_investigation_steps_sum[$__range])) / sum(max_over_time(guardia_investigation_steps_count[$__range]))",
                  "none", "#B48CFF", 1), 5, 5)
    grid.add(stat("Tokens per investigation", "Mean input and output tokens (gen_ai.client.token.usage). With the fake provider these are the scripted turns' counts, not a model's.",
                  f"sum(max_over_time(gen_ai_client_token_usage_sum[$__range])) / {runs}", "none", "#5E9EFF", 0), 5, 5)
    grid.add(stat("Time to diagnosis", "Mean time from the alert to a validated diagnosis (gen_ai.client.operation.duration).",
                  "sum(max_over_time(gen_ai_client_operation_duration_seconds_sum[$__range])) / sum(max_over_time(gen_ai_client_operation_duration_seconds_count[$__range]))",
                  "s", "#3DD68C", 2), 4, 5)

    grid.section("Logs")
    grid.add({
        "type": "logs",
        "title": "Warnings and errors",
        "description": "Warnings and errors from every Mercadillo service. A line with a trace links to it.",
        "datasource": LOKI,
        "targets": [{"datasource": LOKI, "refId": "A",
                     "expr": '{service_namespace="mercadillo"} | detected_level=~"warn|error"'}],
        "options": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending", "enableLogDetails": True,
                    "dedupStrategy": "none", "prettifyLogMessage": False},
    }, 24, 9)

    return {
        "uid": "mercadillo-overview",
        "title": "Mercadillo · Overview",
        "description": "Live health of the fictional marketplace GuardiAI watches.",
        "tags": ["mercadillo", "guardia"],
        "editable": False,
        "graphTooltip": 1,
        "refresh": "10s",
        "time": {"from": "now-15m", "to": "now"},
        "timepicker": {},
        "timezone": "browser",
        "schemaVersion": 41,
        "version": 1,
        "templating": {"list": []},
        "annotations": {"list": []},
        "links": [],
        "panels": grid.panels,
    }


def render() -> str:
    """Renders the dashboard as the JSON that is committed.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    return json.dumps(build(), indent=2, ensure_ascii=False) + "\n"


if __name__ == "__main__":
    if "--check" in sys.argv:
        sys.exit(0 if OUTPUT.read_text(encoding="utf-8") == render() else 1)
    OUTPUT.write_text(render(), encoding="utf-8")
    print(f"wrote {OUTPUT}")
