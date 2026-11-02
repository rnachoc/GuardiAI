/**
 * A stand-in for a Slack-style incoming webhook, so escalations can be seen without a real Slack. It keeps
 * the last 200 messages in memory: POST /hook takes {"text": …}, GET /messages returns them as JSON, and GET /
 * shows them on a page that escapes every message and allows no scripts.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { createServer } from "node:http";
import { pathToFileURL } from "node:url";

const LIMIT = 200;
const MAX_BODY = 64 * 1024;

/**
 * Escapes text for HTML.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function escape(text) {
  return text.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

/**
 * Renders the page of messages, newest first.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function page(messages) {
  const items = messages.slice().reverse().map((m) => `<li><time>${escape(m.at)}</time><p>${escape(m.text)}</p></li>`).join("");
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="5"><title>GuardiAI Escalations</title><style>
:root{color-scheme:dark}body{margin:0;background:#0b0b0d;color:#f5f5f7;font:16px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
main{max-width:760px;margin:0 auto;padding:48px 16px}h1{font-size:40px;letter-spacing:-.02em;margin:0 0 8px}
.lede{color:#a1a1a6;margin:0 0 32px}ul{list-style:none;margin:0;padding:0}li{background:#16161a;border:1px solid #2a2a30;border-radius:14px;padding:16px 20px;margin:0 0 12px}
time{color:#e50914;font-size:13px;font-weight:600}p{margin:6px 0 0;overflow-wrap:anywhere}</style></head>
<body><main><h1>Escalations</h1><p class="lede">What GuardiAI sent to a person. Refreshes every five seconds.</p>
${items ? `<ul>${items}</ul>` : `<p class="lede">Nothing yet. GuardiAI escalates when a remediation fails to recover the system.</p>`}</main></body></html>`;
}

/**
 * Creates the server; messages live only as long as it does.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function createWebhookMock() {
  const messages = [];
  return createServer((req, res) => {
    /**
     * Sends a response with the given status, type and body.
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    const send = (status, type, body) => {
      res.writeHead(status, { "Content-Type": type, "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'", "X-Content-Type-Options": "nosniff" });
      res.end(body);
    };
    if (req.method === "POST" && req.url === "/hook") {
      let body = "";
      let tooLarge = false;
      req.on("data", (chunk) => {
        body += chunk;
        if (body.length > MAX_BODY) {
          tooLarge = true;
          req.destroy();
        }
      });
      req.on("end", () => {
        if (tooLarge) {
          return;
        }
        let text;
        try {
          text = JSON.parse(body).text;
        } catch {
          text = undefined;
        }
        if (typeof text !== "string" || text.length === 0) {
          return send(400, "application/json", JSON.stringify({ error: "expected a JSON body with a non-empty text" }));
        }
        messages.push({ at: new Date().toISOString(), text });
        messages.splice(0, Math.max(0, messages.length - LIMIT));
        send(200, "text/plain", "ok");
      });
    } else if (req.method === "GET" && req.url === "/messages") {
      send(200, "application/json", JSON.stringify(messages));
    } else if (req.method === "GET" && req.url === "/healthz") {
      send(200, "text/plain", "ok");
    } else if (req.method === "GET" && req.url === "/") {
      send(200, "text/html; charset=utf-8", page(messages));
    } else {
      send(404, "text/plain", "not found");
    }
  });
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  createWebhookMock().listen(Number(process.env.PORT ?? "8091"), "0.0.0.0");
}
