/**
 * The names GuardiAI's scenarios may use: the services of Mercadillo, the faults the injector knows, and
 * the actions of the policy engine's catalog. Kept in one place so a scenario cannot name something that
 * does not exist.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */

export const SERVICES = [
  "gateway", "catalog", "cart", "checkout", "payments", "inventory", "notifier",
  "postgres", "redis", "kafka",
] as const;

export const FAMILIES = [
  "latency", "availability", "resources", "database", "messaging", "configuration", "deploy", "certificates",
] as const;

export const KINDS = ["incident", "decoy", "injection"] as const;

/** The actions the policy engine will offer (brief, section 5). `none` means the right move is to act on nothing. */
export const ACTIONS = [
  "none", "restart_service", "scale_deployment", "rollback_deploy", "toggle_flag", "clear_cache",
  "pause_consumer", "resume_consumer", "increase_pool_size", "failover",
] as const;

/** Toxiproxy proxies, each sitting on one connection between two parts of Mercadillo. */
export const PROXIES = {
  "checkout-payments": { from: "checkout", to: "payments" },
  "checkout-inventory": { from: "checkout", to: "inventory" },
  "catalog-postgres": { from: "catalog", to: "postgres" },
  "cart-redis": { from: "cart", to: "redis" },
} as const;

export type Service = (typeof SERVICES)[number];
export type Action = (typeof ACTIONS)[number];
