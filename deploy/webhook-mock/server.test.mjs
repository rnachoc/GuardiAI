/**
 * Tests for the webhook stand-in: it stores Slack-style messages, refuses anything else, and shows them escaped.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { strict as assert } from "node:assert";
import { after, before, test } from "node:test";
import { createWebhookMock } from "./server.mjs";

let base;
const server = createWebhookMock();

before(async () => {
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

/**
 * Posts a body to the hook.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function post(body) {
  return fetch(`${base}/hook`, { method: "POST", headers: { "Content-Type": "application/json" }, body });
}

test("stores a message and returns it", async () => {
  assert.equal((await post(JSON.stringify({ text: "GuardiAI needs a person: p-1 was reverted" }))).status, 200);
  const messages = await (await fetch(`${base}/messages`)).json();
  assert.equal(messages.at(-1).text, "GuardiAI needs a person: p-1 was reverted");
});

test("refuses a body without text", async () => {
  assert.equal((await post("{}")).status, 400);
  assert.equal((await post("not json")).status, 400);
});

test("shows messages escaped, on a page that runs no scripts", async () => {
  await post(JSON.stringify({ text: "<script>alert(1)</script>" }));
  const res = await fetch(base);
  const html = await res.text();
  assert.match(res.headers.get("content-security-policy"), /default-src 'none'/);
  assert.match(html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.doesNotMatch(html, /<script>/);
});
