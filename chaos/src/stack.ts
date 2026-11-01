/**
 * The three things guardia-chaos acts on: Toxiproxy's API, the Docker Engine API over its socket, and
 * Loki's push API for planted log lines. Each client does only what the scenarios need.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { request } from "node:http";
import type { Fault } from "./timeline.ts";

export interface Endpoints {
  toxiproxyUrl: string;
  lokiUrl: string;
  dockerSocket: string;
  composeProject: string;
}

/**
 * Sends one HTTP request, over TCP or a Unix socket, and returns the status and body.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function http(method: string, url: string, body?: unknown, socketPath?: string): Promise<{ status: number; body: string }> {
  const target = new URL(url);
  const payload = body === undefined ? undefined : JSON.stringify(body);
  return new Promise((resolve, reject) => {
    const req = request({
      method,
      socketPath,
      host: socketPath ? undefined : target.hostname,
      port: socketPath ? undefined : target.port,
      path: target.pathname + target.search,
      headers: payload ? { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(payload) } : {},
      timeout: 15000,
    }, (res) => {
      let text = "";
      res.setEncoding("utf8");
      res.on("data", (chunk: string) => { text += chunk; });
      res.on("end", () => resolve({ status: res.statusCode ?? 0, body: text }));
    });
    req.on("timeout", () => req.destroy(new Error(`${method} ${url} timed out`)));
    req.on("error", reject);
    if (payload) {
      req.write(payload);
    }
    req.end();
  });
}

/**
 * Fails with the response when a status is not one of the expected ones.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function expect(what: string, response: { status: number; body: string }, ...ok: number[]): void {
  if (!ok.includes(response.status)) {
    throw new Error(`${what} answered ${response.status}: ${response.body.slice(0, 200)}`);
  }
}

/**
 * Builds the Toxiproxy toxic for a latency or timeout fault. The toxic's name is fixed per step, so
 * applying twice replaces nothing silently and reverting knows what to remove.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function toxicFor(fault: Fault, step: number): { name: string; type: string; stream: string; toxicity: number; attributes: Record<string, number> } {
  if (fault.type === "latency") {
    return { name: `guardia-step-${step}`, type: "latency", stream: "downstream", toxicity: 1,
      attributes: { latency: fault.latencyMs, jitter: fault.jitterMs } };
  }
  if (fault.type === "timeout") {
    return { name: `guardia-step-${step}`, type: "timeout", stream: "downstream", toxicity: 1,
      attributes: { timeout: fault.afterMs } };
  }
  throw new Error(`a ${fault.type} fault is not a toxic`);
}

/**
 * Finds the container of a Compose service in the project.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
async function containerOf(endpoints: Endpoints, service: string): Promise<string> {
  const filters = encodeURIComponent(JSON.stringify({ label: [
    `com.docker.compose.project=${endpoints.composeProject}`, `com.docker.compose.service=${service}`] }));
  const response = await http("GET", `http://docker/containers/json?all=1&filters=${filters}`, undefined, endpoints.dockerSocket);
  expect(`listing containers of ${service}`, response, 200);
  const found = JSON.parse(response.body) as { Id: string }[];
  if (found.length !== 1 || !found[0]) {
    throw new Error(`expected one container for ${service}, found ${found.length}`);
  }
  return found[0].Id;
}

/**
 * Applies one fault to the running stack.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export async function apply(endpoints: Endpoints, fault: Fault, step: number): Promise<void> {
  switch (fault.type) {
    case "latency":
    case "timeout":
      expect(`adding a toxic to ${fault.proxy}`, await http("POST",
        `${endpoints.toxiproxyUrl}/proxies/${fault.proxy}/toxics`, toxicFor(fault, step)), 200);
      return;
    case "kill":
      expect(`killing ${fault.service}`, await http("POST",
        `http://docker/containers/${await containerOf(endpoints, fault.service)}/kill`, undefined, endpoints.dockerSocket), 204);
      return;
    case "pause":
      expect(`pausing ${fault.service}`, await http("POST",
        `http://docker/containers/${await containerOf(endpoints, fault.service)}/pause`, undefined, endpoints.dockerSocket), 204);
      return;
    case "plant_log": {
      const nanoseconds = `${BigInt(Date.now()) * 1000000n}`;
      expect(`planting a log line in ${fault.service}`, await http("POST", `${endpoints.lokiUrl}/loki/api/v1/push`, {
        streams: [{
          stream: { service_name: fault.service, service_namespace: "mercadillo", deployment_environment_name: "local" },
          values: [[nanoseconds, fault.message, { severity_text: fault.level, detected_level: fault.level.toLowerCase() }]],
        }],
      }), 204);
      return;
    }
  }
}

/**
 * Reverts one fault. Reverting something already reverted is harmless.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export async function revert(endpoints: Endpoints, fault: Fault, step: number): Promise<void> {
  switch (fault.type) {
    case "latency":
    case "timeout":
      expect(`removing a toxic from ${fault.proxy}`, await http("DELETE",
        `${endpoints.toxiproxyUrl}/proxies/${fault.proxy}/toxics/guardia-step-${step}`), 204, 404);
      return;
    case "kill":
      expect(`starting ${fault.service}`, await http("POST",
        `http://docker/containers/${await containerOf(endpoints, fault.service)}/start`, undefined, endpoints.dockerSocket), 204, 304);
      return;
    case "pause":
      expect(`unpausing ${fault.service}`, await http("POST",
        `http://docker/containers/${await containerOf(endpoints, fault.service)}/unpause`, undefined, endpoints.dockerSocket), 204, 409);
      return;
    case "plant_log":
      return;
  }
}

/**
 * Removes every toxic from every proxy, as a last resort after an interrupted run.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export async function resetToxics(endpoints: Endpoints): Promise<void> {
  expect("resetting Toxiproxy", await http("POST", `${endpoints.toxiproxyUrl}/reset`), 204);
}
