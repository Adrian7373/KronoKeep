import { test } from "node:test";
import assert from "node:assert/strict";
import {
  chunk,
  classifyPasswordCheck,
  collectObjectPaths,
  LIST_PAGE,
  type ListPage,
  readPassword,
  type StorageEntry,
} from "./account.ts";

/** A fake bucket: a flat list of object paths, listed folder by folder like Storage does. */
function fakeBucket(objects: string[]): { list: ListPage; calls: string[] } {
  const calls: string[] = [];
  const list: ListPage = (folder, offset, limit) => {
    calls.push(`${folder}@${offset}`);
    const prefix = folder ? `${folder}/` : "";
    const seen = new Map<string, StorageEntry>();
    for (const path of objects.filter((p) => p.startsWith(prefix)).sort()) {
      const rest = path.slice(prefix.length);
      const slash = rest.indexOf("/");
      const name = slash === -1 ? rest : rest.slice(0, slash);
      if (!seen.has(name)) seen.set(name, { name, id: slash === -1 ? `id-${path}` : null });
    }
    return Promise.resolve([...seen.values()].slice(offset, offset + limit));
  };
  return { list, calls };
}

test("collects every object under the user's folder and nothing outside it", async () => {
  const { list } = fakeBucket([
    "u1/a1/d1.jpg",
    "u1/a1/d2.pdf",
    "u1/a2/d3.png",
    "u1/a2/deeper/odd.jpg",
    "u2/a9/other.jpg",
  ]);
  const paths = await collectObjectPaths(list, "u1");
  assert.deepEqual(paths.sort(), ["u1/a1/d1.jpg", "u1/a1/d2.pdf", "u1/a2/d3.png", "u1/a2/deeper/odd.jpg"]);
});

test("an empty or missing folder gives no paths", async () => {
  const { list } = fakeBucket(["u2/a/x.jpg"]);
  assert.deepEqual(await collectObjectPaths(list, "u1"), []);
});

test("pages through folders larger than one list page", async () => {
  const many = Array.from({ length: LIST_PAGE + 5 }, (_, i) => `u1/a1/${String(i).padStart(5, "0")}.jpg`);
  const { list, calls } = fakeBucket(many);
  const paths = await collectObjectPaths(list, "u1");
  assert.equal(paths.length, LIST_PAGE + 5);
  assert.ok(calls.includes(`u1/a1@${LIST_PAGE}`));
});

test("skips the empty-folder placeholder", async () => {
  const { list } = fakeBucket(["u1/a1/.emptyFolderPlaceholder", "u1/a1/d.jpg"]);
  assert.deepEqual(await collectObjectPaths(list, "u1"), ["u1/a1/d.jpg"]);
});

test("refuses runaway nesting instead of recursing forever", async () => {
  const list: ListPage = () => Promise.resolve([{ name: "loop", id: null }]);
  await assert.rejects(collectObjectPaths(list, "u1"), /nesting/);
});

test("chunk splits into batches", () => {
  assert.deepEqual(chunk([1, 2, 3, 4, 5], 2), [[1, 2], [3, 4], [5]]);
  assert.deepEqual(chunk([], 3), []);
  assert.throws(() => chunk([1], 0));
});

test("readPassword accepts only a non-empty string", () => {
  assert.equal(readPassword({ password: "secret" }), "secret");
  assert.equal(readPassword({ password: "" }), null);
  assert.equal(readPassword({ password: 12 }), null);
  assert.equal(readPassword({ password: "x".repeat(1025) }), null);
  assert.equal(readPassword(null), null);
  assert.equal(readPassword({}), null);
});

test("classifies the re-authentication result", () => {
  assert.equal(classifyPasswordCheck(null), "ok");
  assert.equal(classifyPasswordCheck({ status: 400, code: "invalid_credentials" }), "invalid_password");
  assert.equal(classifyPasswordCheck({ status: 429 }), "rate_limited");
  assert.equal(classifyPasswordCheck({ status: 400, code: "over_request_rate_limit" }), "rate_limited");
  assert.equal(classifyPasswordCheck({ status: 500 }), "error");
});
