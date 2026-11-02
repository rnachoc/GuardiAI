/**
 * Tests for the flags service: it serves each flag, toggles it, and refuses what it does not know.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { strict as assert } from "node:assert";
import { after, before, test } from "node:test";
import { createFlagsServer } from "./server.mjs";

let base;
const server = createFlagsServer({ "checkout.latest_release": true });

before(async () => {
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

/**
 * Sets a flag and returns the response.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function put(name, body) {
  return fetch(`${base}/flags/${name}`, { method: "PUT", headers: { "Content-Type": "application/json" }, body });
}

test("serves a flag, turns it off and on again", async () => {
  assert.deepEqual(await (await fetch(`${base}/flags/checkout.latest_release`)).json(), { name: "checkout.latest_release", enabled: true });
  assert.equal((await put("checkout.latest_release", JSON.stringify({ enabled: false }))).status, 200);
  assert.equal((await (await fetch(`${base}/flags`)).json())["checkout.latest_release"], false);
  assert.equal((await put("checkout.latest_release", JSON.stringify({ enabled: true }))).status, 200);
});

test("an unknown flag is not found and cannot be created; a malformed body is refused", async () => {
  assert.equal((await fetch(`${base}/flags/nope.flag`)).status, 404);
  assert.equal((await put("nope.flag", JSON.stringify({ enabled: true }))).status, 404);
  assert.equal((await put("checkout.latest_release", "{\"enabled\":\"yes\"}")).status, 400);
});
