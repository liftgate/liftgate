import assert from "node:assert/strict";
import { test } from "node:test";
import { MAX_DOTENV_BYTES, parseDotenv } from "./dotenv.ts";

const vars = (text: string) => Object.fromEntries(parseDotenv(text).vars.map((v) => [v.name, v.value]));

test("parseDotenv reads plain and exported assignments and skips blank and comment lines", () => {
  assert.deepEqual(parseDotenv("# shop\n\nAPI_URL=https://api.example.com\nexport LOG_LEVEL=info\n  SPACED = yes  \n"), {
    vars: [
      { name: "API_URL", value: "https://api.example.com" },
      { name: "LOG_LEVEL", value: "info" },
      { name: "SPACED", value: "yes" },
    ],
    skipped: [],
    duplicates: [],
  });
});

test("a space and a hash end an unquoted value, a hash inside it does not", () => {
  assert.deepEqual(vars("COLOR=#ff0000\nMODE=fast # the default\nEMPTY=\n"), { COLOR: "#ff0000", MODE: "fast", EMPTY: "" });
});

test("single quotes are literal", () => {
  assert.deepEqual(vars("SQL='select * from t where a = \"b\" # not a comment \\n'"), { SQL: 'select * from t where a = "b" # not a comment \\n' });
});

test("double quotes allow escapes and span lines", () => {
  assert.deepEqual(vars('GREETING="hello\\nworld\\t\\"quoted\\" \\\\ done" # trailing\nKEY="-----BEGIN KEY-----\nabc\n-----END KEY-----"\nAFTER=1'), {
    GREETING: 'hello\nworld\t"quoted" \\ done',
    KEY: "-----BEGIN KEY-----\nabc\n-----END KEY-----",
    AFTER: "1",
  });
});

test("lines that are not assignments are reported by number, never by value", () => {
  const parsed = parseDotenv("GOOD=1\nnot an assignment\n1BAD=secret-value\nBAD-NAME=x\nOPEN=\"never closed\nLATER=2\nHALF='open");
  assert.deepEqual(parsed.vars, [
    { name: "GOOD", value: "1" },
    { name: "LATER", value: "2" },
  ]);
  assert.deepEqual(parsed.skipped, [2, 3, 4, 5, 7]);
  assert.ok(!JSON.stringify(parsed).includes("secret-value"));
});

test("a repeated name keeps the last value and is noted once", () => {
  assert.deepEqual(parseDotenv("A=1\nB=2\nA=3\nA=4\r\n"), { vars: [{ name: "A", value: "4" }, { name: "B", value: "2" }], skipped: [], duplicates: ["A"] });
});

test("input over 256 KB is refused", () => {
  assert.throws(() => parseDotenv(`A=${"x".repeat(MAX_DOTENV_BYTES)}`), /256 KB/);
  assert.equal(parseDotenv(`A=${"x".repeat(MAX_DOTENV_BYTES - 2)}`).vars[0].value.length, MAX_DOTENV_BYTES - 2);
});
