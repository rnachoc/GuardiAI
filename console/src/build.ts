/**
 * Builds the replay console: injects the data into the page template and writes console/dist/index.html,
 * one self-contained file that GitHub Pages or any browser can open with no server.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { buildData } from "./build-data.ts";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, "..", "..");
const data = await buildData(ROOT);
const template = readFileSync(join(HERE, "..", "index.template.html"), "utf8");
const page = template.replace("/*__DATA__*/null", JSON.stringify(data).replaceAll("</", "<\\/"));
mkdirSync(join(HERE, "..", "dist"), { recursive: true });
writeFileSync(join(HERE, "..", "dist", "index.html"), page);
console.log(`wrote console/dist/index.html: ${data.scenarios.length} scenarios, ${Math.round(page.length / 1024)} KB`);
