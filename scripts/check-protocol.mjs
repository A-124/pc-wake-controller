#!/usr/bin/env node
/**
 * Cross-component protocol gate. Recomputes the normative vectors in
 * documentation/PROTOCOL.md §8 with Node's crypto and fails if the document,
 * and therefore every implementation contract, drifts.
 */
import { createHash, createHmac } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const protocol = readFileSync(join(root, "documentation", "PROTOCOL.md"), "utf8");

const token = "test-token-0123456789";
const timestamp = "1720000000";
const nonce = "AAECAwQFBgcICQoLDA0ODw";

const b64url = (buf) => buf.toString("base64url");
const sha256hex = (buf) => createHash("sha256").update(buf).digest("hex");
const canonical = (method, path, ts, nonceValue, body) =>
  [method, path, ts, nonceValue, sha256hex(body)].join("\n");
const sign = (canonicalString) =>
  b64url(createHmac("sha256", token).update(canonicalString, "utf8").digest());

const postBody = Buffer.from('{"graceSeconds":15}', "utf8");
const postCanonical = canonical("POST", "/api/v1/power/shutdown", timestamp, nonce, postBody);
const getCanonical = canonical("GET", "/api/v1/status", timestamp, nonce, Buffer.alloc(0));

const expected = {
  bodySha: "41d72c15ac4723b83324e5ff7a275bc1a6bbe34fa3de3a7c27d249c1b97eacb5",
  postSignature: "qEPDkh0eZIiTzT1ZGTXR4z398xh3XNG3iL6buuhenkQ",
  emptySha: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  getSignature: "6S_CySELEsBTeWGBaBgzKQqWjAkz3CMQTwxPMNKyVUI",
};

const actual = {
  bodySha: sha256hex(postBody),
  postSignature: sign(postCanonical),
  emptySha: sha256hex(Buffer.alloc(0)),
  getSignature: sign(getCanonical),
};

let failed = false;
for (const [key, value] of Object.entries(expected)) {
  if (actual[key] !== value) {
    console.error(`FAIL ${key}: expected ${value}, got ${actual[key]}`);
    failed = true;
  } else {
    console.log(`PASS ${key} = ${value}`);
  }
  if (!protocol.includes(value)) {
    console.error(`FAIL ${key}: value is not documented in PROTOCOL.md`);
    failed = true;
  }
}
if (failed) {
  process.exit(1);
}
console.log("Protocol vectors match documentation and reference implementation.");
