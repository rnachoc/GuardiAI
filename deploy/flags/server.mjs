/**
 * Mercadillo's feature flags: a small service that holds each flag's state, so GuardiAI's toggle_flag has a
 * real switch to turn. GET /flags lists them, GET /flags/<name> reads one, and PUT /flags/<name> with
 * {"enabled": true|false} sets it. Flags come from flags.json at start; unknown flags cannot be created.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import { pathToFileURL } from "node:url";

/**
 * Creates the server over an initial set of flags.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function createFlagsServer(initial) {
  const flags = new Map(Object.entries(initial));
  return createServer((req, res) => {
    /**
     * Sends a JSON answer.
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    const send = (status, body) => {
      res.writeHead(status, { "Content-Type": "application/json" });
      res.end(JSON.stringify(body));
    };
    const match = /^\/flags\/([a-z][a-z0-9_.-]{0,80})$/.exec(req.url ?? "");
    if (req.method === "GET" && req.url === "/flags") {
      return send(200, Object.fromEntries(flags));
    }
    if (req.method === "GET" && req.url === "/healthz") {
      return send(200, { ok: true });
    }
    if (!match) {
      return send(404, { error: "not found" });
    }
    const name = match[1];
    if (!flags.has(name)) {
      return send(404, { error: `no flag named ${name}` });
    }
    if (req.method === "GET") {
      return send(200, { name, enabled: flags.get(name) });
    }
    if (req.method !== "PUT") {
      return send(405, { error: "use GET or PUT" });
    }
    let body = "";
    req.on("data", (chunk) => { body += chunk; if (body.length > 1024) req.destroy(); });
    req.on("end", () => {
      let enabled;
      try {
        enabled = JSON.parse(body).enabled;
      } catch {
        enabled = undefined;
      }
      if (typeof enabled !== "boolean") {
        return send(400, { error: "expected {\"enabled\": true or false}" });
      }
      flags.set(name, enabled);
      send(200, { name, enabled });
    });
  });
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  createFlagsServer(JSON.parse(readFileSync(new URL("./flags.json", import.meta.url), "utf8"))).listen(8092, "0.0.0.0");
}
