#!/usr/bin/env node
// Fails when the npm tarball would ship the wrong files.
//
//   npm pack --dry-run --ignore-scripts --json | node .github/scripts/assert-pack-contents.mjs
//
// CI and the example app read the repo tree, so only this sees what consumers
// get. The Core Data marker is the case that matters most: without
// .xccurrentversion momc only warns and exits 0, and the .momd it compiles
// declares no current version, so a consumer's build stays green.
//
// Run it from the repo root: the expected model versions come from the tree, so
// a new version is covered without touching this file.

import { readFileSync, readdirSync } from 'node:fs';

const MODEL_DIR = 'ios/Database/SyncProvider.xcdatamodeld';
const MARKER = `${MODEL_DIR}/.xccurrentversion`;
const NATIVE_TESTS = /^(ios\/Tests|android\/src\/(test|androidTest))\//;

const [pack] = JSON.parse(readFileSync(0, 'utf8'));
const packed = pack.files.map(({ path }) => path);

const current =
  /<key>_XCCurrentVersionName<\/key>\s*<string>([^<]+)<\/string>/.exec(
    readFileSync(MARKER, 'utf8')
  )?.[1];

const versions = readdirSync(MODEL_DIR).filter((entry) =>
  entry.endsWith('.xcdatamodel')
);

const required = [
  ...new Set([
    MARKER,
    `${MODEL_DIR}/${current}/contents`,
    ...versions.map((version) => `${MODEL_DIR}/${version}/contents`),
  ]),
];

const problems = [
  ...required
    .filter((path) => !packed.includes(path))
    .map((path) => `missing from the tarball: ${path}`),
  ...packed
    .filter((path) => NATIVE_TESTS.test(path))
    .map((path) => `native test in the tarball: ${path}`),
];

if (problems.length > 0) {
  problems.forEach((problem) => console.error(`::error::${problem}`));
  process.exit(1);
}

console.log(
  `npm pack contents OK: ${packed.length} files, current model ${current}, ${versions.length} model version(s), no native tests.`
);
