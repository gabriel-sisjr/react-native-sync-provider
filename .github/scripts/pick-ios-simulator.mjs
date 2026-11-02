#!/usr/bin/env node
// Picks the simulator for `test-ios` and prints its UDID on stdout.
//
//   xcrun simctl list -j devices available |
//     node .github/scripts/pick-ios-simulator.mjs "iPhone 17" "iPhone 17 Pro"
//
// The arguments are a preference, most wanted first. The newest iOS runtime
// decides, and when it has none of them any other iPhone on it is used, so the
// next image rotation cannot leave the job without a device.

import { readFileSync } from 'node:fs';

const preferred = process.argv.slice(2);
const { devices } = JSON.parse(readFileSync(0, 'utf8'));

// Keys look like com.apple.CoreSimulator.SimRuntime.iOS-26-5. Anchoring the end
// skips the other platforms and runtimes tied to one model (iOS-27-2-iPhone19-4).
const RUNTIME = /\.iOS-(\d+)-(\d+)(?:-(\d+))?$/;

const runtimes = Object.entries(devices)
  .flatMap(([key, simulators]) => {
    const match = RUNTIME.exec(key);
    return match
      ? [
          {
            version: match.slice(1).map((part) => Number(part ?? 0)),
            simulators,
          },
        ]
      : [];
  })
  .sort((a, b) =>
    a.version.reduce((order, part, i) => order || b.version[i] - part, 0)
  );

for (const { version, simulators } of runtimes) {
  const iPhones = simulators
    .filter(({ name }) => name.startsWith('iPhone '))
    .sort((a, b) => a.name.localeCompare(b.name));
  const chosen =
    preferred
      .map((wanted) => iPhones.find(({ name }) => name === wanted))
      .find(Boolean) ?? iPhones[0];

  if (chosen) {
    // Only the UDID goes to stdout: the workflow captures it.
    console.error(`Testing on ${chosen.name}, iOS ${version.join('.')}`);
    console.log(chosen.udid);
    process.exit(0);
  }
}

console.error(
  `No available iPhone simulator. Runtimes: ${Object.keys(devices).join(', ')}`
);
process.exit(1);
