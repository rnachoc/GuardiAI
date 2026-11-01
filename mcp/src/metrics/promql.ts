/**
 * The PromQL that GuardiAI's agents may use: a parser that accepts only an allow-listed subset, the
 * limits that keep a query cheap, and an evaluator that answers the same subset over recorded series in
 * replay mode. Live mode validates with the same parser before forwarding to Prometheus, so both modes
 * accept exactly the same queries.
 *
 * Supported: selectors with = != =~ !~ matchers, rate and increase over a range, sum avg min max count
 * with `by`, histogram_quantile, vector, numbers, + - * /, and `or`. Rates are computed from the first and
 * last sample in the range, without Prometheus's extrapolation, so replayed values can differ slightly.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import type { Series } from "../shared/replay.ts";

export const LIMITS = { maxQueryLength: 1000, maxNodes: 60, maxRangeSeconds: 600, maxRegexLength: 100, lookbackSeconds: 300 };

export type Node =
  | { type: "number"; value: number }
  | { type: "selector"; matchers: Matcher[]; rangeSeconds?: number }
  | { type: "call"; fn: "rate" | "increase" | "histogram_quantile" | "vector"; args: Node[] }
  | { type: "agg"; op: "sum" | "avg" | "min" | "max" | "count"; by: string[] | null; expr: Node }
  | { type: "binary"; op: "+" | "-" | "*" | "/" | "or"; left: Node; right: Node };

export interface Matcher {
  label: string;
  op: "=" | "!=" | "=~" | "!~";
  value: string;
}

export interface Sample {
  labels: Record<string, string>;
  value: number;
}

const AGGREGATIONS = new Set(["sum", "avg", "min", "max", "count"]);
const FUNCTIONS = new Set(["rate", "increase", "histogram_quantile", "vector"]);

/**
 * Raised for a query outside the allowed subset or over a limit. The message is safe to show the agent.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export class QueryRejected extends Error {
}

/**
 * Splits a query into tokens.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function tokenize(query: string): string[] {
  const tokens: string[] = [];
  const pattern = /\s*(=~|!~|!=|"(?:\\.|[^"\\])*"|[A-Za-z_:][A-Za-z0-9_:.]*|\d+(?:\.\d+)?(?:e[+-]?\d+)?[smh]?|[(){}\[\],=+\-*\/])/gy;
  let index = 0;
  while (index < query.length) {
    if (/^\s*$/.test(query.slice(index))) {
      break;
    }
    pattern.lastIndex = index;
    const match = pattern.exec(query);
    if (!match || match[1] === undefined) {
      throw new QueryRejected(`unexpected character at position ${index}: '${query.slice(index, index + 10)}'`);
    }
    tokens.push(match[1]);
    index = pattern.lastIndex;
  }
  return tokens;
}

/**
 * Parses a duration such as 1m or 30s into seconds.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function duration(token: string): number {
  const match = /^(\d+)([smh])$/.exec(token);
  if (!match) {
    throw new QueryRejected(`'${token}' is not a duration like 30s, 1m or 5m`);
  }
  return Number(match[1]) * { s: 1, m: 60, h: 3600 }[match[2] as "s" | "m" | "h"];
}

/**
 * Refuses a regular expression that could make the replay evaluator slow: too long, or with a quantifier
 * applied to a group, which is how catastrophic backtracking starts.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function checkRegex(value: string): void {
  if (value.length > LIMITS.maxRegexLength) {
    throw new QueryRejected(`a regular expression may have at most ${LIMITS.maxRegexLength} characters`);
  }
  if (/\)[*+?{]/.test(value) || /\\[1-9]/.test(value)) {
    throw new QueryRejected("a regular expression may not repeat a group or use back-references");
  }
  try {
    new RegExp(value);
  } catch {
    throw new QueryRejected(`'${value}' is not a valid regular expression`);
  }
}

/**
 * Parses and validates a query, or throws QueryRejected with the reason.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function parse(query: string): Node {
  if (query.length > LIMITS.maxQueryLength) {
    throw new QueryRejected(`a query may have at most ${LIMITS.maxQueryLength} characters`);
  }
  const tokens = tokenize(query);
  let position = 0;
  let nodes = 0;
  /**
   * Returns the next token without consuming it.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const peek = () => tokens[position];
  /**
   * Consumes the next token, optionally requiring it to be a given one.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const next = (expected?: string): string => {
    const token = tokens[position];
    if (token === undefined || (expected !== undefined && token !== expected)) {
      throw new QueryRejected(`expected '${expected ?? "more"}' but found '${token ?? "the end"}'`);
    }
    position += 1;
    return token;
  };
  /**
   * Counts a node, refusing queries that grow too large.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const count = <T extends Node>(node: T): T => {
    nodes += 1;
    if (nodes > LIMITS.maxNodes) {
      throw new QueryRejected(`a query may have at most ${LIMITS.maxNodes} parts`);
    }
    return node;
  };
  /**
   * Parses `by (a, b)`.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const byClause = (): string[] => {
    next("by");
    next("(");
    const labels: string[] = [];
    while (peek() !== ")") {
      labels.push(next());
      if (peek() === ",") {
        next(",");
      }
    }
    next(")");
    return labels;
  };
  /**
   * Parses a selector: a metric name with optional matchers and an optional range.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const selector = (name: string | undefined): Node => {
    const matchers: Matcher[] = name ? [{ label: "__name__", op: "=", value: name }] : [];
    if (peek() === "{") {
      next("{");
      while (peek() !== "}") {
        const label = next();
        const op = next() as Matcher["op"];
        if (!["=", "!=", "=~", "!~"].includes(op)) {
          throw new QueryRejected(`'${op}' is not a label matcher`);
        }
        const quoted = next();
        if (!quoted.startsWith('"')) {
          throw new QueryRejected(`a label value must be quoted, found '${quoted}'`);
        }
        const value = JSON.parse(quoted) as string;
        if (op === "=~" || op === "!~") {
          checkRegex(value);
        }
        matchers.push({ label, op, value });
        if (peek() === ",") {
          next(",");
        }
      }
      next("}");
    }
    if (!matchers.some((m) => m.label === "__name__" && m.op === "=")) {
      throw new QueryRejected("a selector must name one metric");
    }
    let rangeSeconds: number | undefined;
    if (peek() === "[") {
      next("[");
      rangeSeconds = duration(next());
      next("]");
      if (rangeSeconds > LIMITS.maxRangeSeconds) {
        throw new QueryRejected(`a range may cover at most ${LIMITS.maxRangeSeconds / 60} minutes`);
      }
    }
    return count({ type: "selector", matchers, rangeSeconds });
  };
  /**
   * Parses a number, a parenthesised expression, an aggregation, a function call or a selector.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const primary = (): Node => {
    const token = next();
    if (/^\d/.test(token)) {
      return count({ type: "number", value: Number(token) });
    }
    if (token === "(") {
      const inner = expression();
      next(")");
      return inner;
    }
    if (token === "{") {
      position -= 1;
      return selector(undefined);
    }
    if (AGGREGATIONS.has(token)) {
      let by: string[] | null = peek() === "by" ? byClause() : null;
      next("(");
      const expr = expression();
      next(")");
      if (peek() === "by") {
        by = byClause();
      }
      return count({ type: "agg", op: token as "sum", by, expr });
    }
    if (peek() === "(") {
      if (!FUNCTIONS.has(token)) {
        throw new QueryRejected(`'${token}' is not an allowed function; allowed: ${[...FUNCTIONS, ...AGGREGATIONS].join(", ")}`);
      }
      next("(");
      const args: Node[] = [expression()];
      while (peek() === ",") {
        next(",");
        args.push(expression());
      }
      next(")");
      const call = count({ type: "call", fn: token as "rate", args });
      checkCall(call);
      return call;
    }
    if (/^[A-Za-z_:]/.test(token)) {
      return selector(token);
    }
    throw new QueryRejected(`unexpected '${token}'`);
  };
  /**
   * Parses * and /.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const term = (): Node => {
    let left = primary();
    while (peek() === "*" || peek() === "/") {
      const op = next() as "*";
      left = count({ type: "binary", op, left, right: primary() });
    }
    return left;
  };
  /**
   * Parses + and -.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const sum = (): Node => {
    let left = term();
    while (peek() === "+" || peek() === "-") {
      const op = next() as "+";
      left = count({ type: "binary", op, left, right: term() });
    }
    return left;
  };
  /**
   * Parses `or`, which binds loosest.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const expression = (): Node => {
    let left = sum();
    while (peek() === "or") {
      next("or");
      left = count({ type: "binary", op: "or", left, right: sum() });
    }
    return left;
  };
  const tree = expression();
  if (position !== tokens.length) {
    throw new QueryRejected(`unexpected '${tokens[position]}' after the end of the query`);
  }
  checkRanges(tree, false);
  return tree;
}

/**
 * Checks a function's arguments.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function checkCall(call: Extract<Node, { type: "call" }>): void {
  const [first, second] = call.args;
  if (call.fn === "rate" || call.fn === "increase") {
    if (call.args.length !== 1 || first?.type !== "selector" || first.rangeSeconds === undefined) {
      throw new QueryRejected(`${call.fn} takes one selector with a range, such as ${call.fn}(metric[1m])`);
    }
  } else if (call.fn === "histogram_quantile") {
    if (call.args.length !== 2 || first?.type !== "number" || first.value < 0 || first.value > 1 || !second) {
      throw new QueryRejected("histogram_quantile takes a number from 0 to 1 and an expression");
    }
  } else if (call.fn === "vector" && (call.args.length !== 1 || first?.type !== "number")) {
    throw new QueryRejected("vector takes one number");
  }
}

/**
 * Refuses a range selector outside rate or increase.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function checkRanges(node: Node, insideRate: boolean): void {
  if (node.type === "selector" && node.rangeSeconds !== undefined && !insideRate) {
    throw new QueryRejected("a range such as [1m] is only allowed inside rate or increase");
  }
  if (node.type === "call") {
    node.args.forEach((arg) => checkRanges(arg, node.fn === "rate" || node.fn === "increase"));
  } else if (node.type === "agg") {
    checkRanges(node.expr, false);
  } else if (node.type === "binary") {
    checkRanges(node.left, false);
    checkRanges(node.right, false);
  }
}

/**
 * Tells whether a series' labels satisfy every matcher.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function matches(labels: Record<string, string>, matchers: Matcher[]): boolean {
  return matchers.every(({ label, op, value }) => {
    const actual = labels[label] ?? "";
    switch (op) {
      case "=": return actual === value;
      case "!=": return actual !== value;
      case "=~": return new RegExp(`^(?:${value})$`).test(actual);
      case "!~": return !new RegExp(`^(?:${value})$`).test(actual);
    }
  });
}

/**
 * Removes the metric name from a label set, as PromQL does for any computed value.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function withoutName(labels: Record<string, string>): Record<string, string> {
  const { __name__: _ignored, ...rest } = labels;
  return rest;
}

/**
 * Builds a stable key for a label set.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function key(labels: Record<string, string>): string {
  return JSON.stringify(Object.entries(labels).sort(([a], [b]) => (a < b ? -1 : 1)));
}

/**
 * Computes the quantile of one histogram, given its buckets' upper bounds and cumulative counts, the way
 * Prometheus does: linear interpolation inside the bucket that holds the rank.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function quantile(q: number, buckets: { le: number; count: number }[]): number {
  const sorted = [...buckets].sort((a, b) => a.le - b.le);
  const total = sorted.at(-1)?.count ?? 0;
  if (sorted.length < 2 || total === 0) {
    return Number.NaN;
  }
  const rank = q * total;
  let previousLe = 0;
  let previousCount = 0;
  for (const bucket of sorted) {
    if (bucket.count >= rank) {
      if (bucket.le === Number.POSITIVE_INFINITY) {
        return previousLe;
      }
      const inBucket = bucket.count - previousCount;
      return inBucket === 0 ? bucket.le : previousLe + (bucket.le - previousLe) * ((rank - previousCount) / inBucket);
    }
    previousLe = bucket.le;
    previousCount = bucket.count;
  }
  return previousLe;
}

/**
 * Evaluates a parsed query at one instant over recorded series. A number evaluates to a scalar.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function evaluate(node: Node, at: number, series: Series[]): Sample[] | number {
  switch (node.type) {
    case "number":
      return node.value;
    case "selector": {
      const found: Sample[] = [];
      for (const item of series) {
        if (!matches(item.labels, node.matchers)) {
          continue;
        }
        const last = [...item.samples].reverse().find(([t]) => t <= at && t > at - LIMITS.lookbackSeconds);
        if (last) {
          found.push({ labels: item.labels, value: last[1] });
        }
      }
      return found;
    }
    case "call": {
      if (node.fn === "vector") {
        return [{ labels: {}, value: (node.args[0] as { value: number }).value }];
      }
      if (node.fn === "rate" || node.fn === "increase") {
        const selector = node.args[0] as Extract<Node, { type: "selector" }>;
        const range = selector.rangeSeconds ?? 60;
        const found: Sample[] = [];
        for (const item of series) {
          if (!matches(item.labels, selector.matchers)) {
            continue;
          }
          const inRange = item.samples.filter(([t]) => t <= at && t > at - range);
          if (inRange.length < 2) {
            continue;
          }
          let increase = 0;
          for (let i = 1; i < inRange.length; i += 1) {
            const step = (inRange[i] as [number, number])[1] - (inRange[i - 1] as [number, number])[1];
            increase += step >= 0 ? step : (inRange[i] as [number, number])[1];
          }
          const span = (inRange.at(-1) as [number, number])[0] - (inRange[0] as [number, number])[0];
          found.push({ labels: withoutName(item.labels), value: node.fn === "rate" ? increase / span : increase * (range / span) });
        }
        return found;
      }
      const q = (node.args[0] as { value: number }).value;
      const input = evaluate(node.args[1] as Node, at, series);
      if (typeof input === "number") {
        throw new QueryRejected("histogram_quantile needs buckets, not a number");
      }
      const groups = new Map<string, { labels: Record<string, string>; buckets: { le: number; count: number }[] }>();
      for (const sample of input) {
        const { le, ...rest } = withoutName(sample.labels);
        if (le === undefined) {
          continue;
        }
        const group = groups.get(key(rest)) ?? { labels: rest, buckets: [] };
        group.buckets.push({ le: le === "+Inf" ? Number.POSITIVE_INFINITY : Number(le), count: sample.value });
        groups.set(key(rest), group);
      }
      return [...groups.values()].map((group) => ({ labels: group.labels, value: quantile(q, group.buckets) }))
        .filter((sample) => !Number.isNaN(sample.value));
    }
    case "agg": {
      const input = evaluate(node.expr, at, series);
      if (typeof input === "number") {
        throw new QueryRejected(`${node.op} needs series, not a number`);
      }
      const groups = new Map<string, { labels: Record<string, string>; values: number[] }>();
      for (const sample of input) {
        const labels = node.by === null ? {} : Object.fromEntries(node.by.filter((l) => sample.labels[l] !== undefined)
          .map((l) => [l, sample.labels[l] as string]));
        const group = groups.get(key(labels)) ?? { labels, values: [] };
        group.values.push(sample.value);
        groups.set(key(labels), group);
      }
      return [...groups.values()].map(({ labels, values }) => ({ labels, value: {
        sum: () => values.reduce((a, b) => a + b, 0),
        avg: () => values.reduce((a, b) => a + b, 0) / values.length,
        min: () => Math.min(...values),
        max: () => Math.max(...values),
        count: () => values.length,
      }[node.op]() }));
    }
    case "binary": {
      const left = evaluate(node.left, at, series);
      const right = evaluate(node.right, at, series);
      if (node.op === "or") {
        if (typeof left === "number" || typeof right === "number") {
          throw new QueryRejected("or joins two sets of series");
        }
        const seen = new Set(left.map((s) => key(withoutName(s.labels))));
        return [...left, ...right.filter((s) => !seen.has(key(withoutName(s.labels))))];
      }
      /**
       * Applies the arithmetic operator.
       * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
       */
      const apply = (a: number, b: number) => ({ "+": a + b, "-": a - b, "*": a * b, "/": a / b })[node.op as "+"];
      if (typeof left === "number" && typeof right === "number") {
        return apply(left, right);
      }
      if (typeof left === "number") {
        return (right as Sample[]).map((s) => ({ labels: withoutName(s.labels), value: apply(left, s.value) }));
      }
      if (typeof right === "number") {
        return left.map((s) => ({ labels: withoutName(s.labels), value: apply(s.value, right) }));
      }
      const byLabels = new Map(right.map((s) => [key(withoutName(s.labels)), s.value]));
      return left.flatMap((s) => {
        const other = byLabels.get(key(withoutName(s.labels)));
        return other === undefined ? [] : [{ labels: withoutName(s.labels), value: apply(s.value, other) }];
      });
    }
  }
}
