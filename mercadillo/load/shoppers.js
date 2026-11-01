// Steady, synthetic shoppers for Mercadillo. Each iteration browses, fills a cart and checks out;
// a few pay with the token the fake provider always declines, so refusals show up too.
// The rate and duration come from the environment, so Compose and CI can choose them.
//
// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import http from "k6/http";
import { check } from "k6";

const BASE = __ENV.GATEWAY_URL || "http://gateway:8080";
const RATE = Number(__ENV.SHOPPERS_PER_SECOND || 4);
const JSON_HEADERS = { headers: { "Content-Type": "application/json" } };

export const options = {
  discardResponseBodies: false,
  scenarios: {
    shoppers: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: __ENV.DURATION || "24h",
      preAllocatedVUs: RATE * 4,
      maxVUs: RATE * 20,
    },
  },
  summaryTrendStats: ["avg", "p(95)", "max"],
};

/**
 * Picks a whole number from 1 to max, inclusive.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function upTo(max) {
  return 1 + Math.floor(Math.random() * max);
}

/**
 * One shopper's visit: browse a category, open a product, fill a cart, check out.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export default function () {
  const categories = ["kitchen", "garden", "books", "audio", "home", "toys", "sports", "office"];
  const category = categories[upTo(categories.length) - 1];
  const listed = http.get(`${BASE}/api/products?category=${category}&limit=5`, { tags: { route: "products" } });
  check(listed, { "products listed": (r) => r.status === 200 });

  const sku = `SKU-${String(upTo(40)).padStart(4, "0")}`;
  const product = http.get(`${BASE}/api/products/${sku}`, { tags: { route: "product" } });
  check(product, { "product found": (r) => r.status === 200 });

  // Most visitors only look around; about one in three buys.
  if (Math.random() > 0.35) {
    return;
  }

  const created = http.post(`${BASE}/api/carts`, null, { tags: { route: "create cart" } });
  if (!check(created, { "cart created": (r) => r.status === 201 })) {
    return;
  }
  const cartId = created.json("id");
  const lines = upTo(3);
  for (let i = 0; i < lines; i += 1) {
    const line = JSON.stringify({ sku: `SKU-${String(upTo(40)).padStart(4, "0")}`, quantity: upTo(2) });
    const added = http.put(`${BASE}/api/carts/${cartId}/items`, line, { ...JSON_HEADERS, tags: { route: "add to cart" } });
    check(added, { "item added": (r) => r.status === 200 });
  }

  const token = Math.random() < 0.05 ? "tok_decline" : "tok_visa";
  const paid = http.post(`${BASE}/api/checkouts`, JSON.stringify({ cartId, paymentToken: token }),
    { ...JSON_HEADERS, tags: { route: "checkout" } });
  check(paid, {
    "checkout answered as expected": (r) => (token === "tok_decline" ? r.status === 402 : r.status === 201),
  });
}
