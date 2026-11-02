#!/usr/bin/env bash
#
# Prints why xcodebuild failed, read from the activity log it left in DerivedData.
#
# The job log cannot show this for `react-native build-ios`. The CLI never reads
# xcodebuild's stderr, which carries "** BUILD FAILED **" and "The following
# build commands failed:", and it pipes stdout through xcbeautify with no flags,
# which drops every line it has no pattern for. A failing script phase can leave
# no trace at all that way. The activity log keeps all of it.
#
# Usage: report-ios-build-failure.sh <activity-log>...
#
# This runs on a job that has already failed, so it reports what it can and never
# fails itself: no `set -e`, and it always exits 0.

set -uo pipefail

# Compiler and tool diagnostics, the marker Xcode adds when a command failed
# without printing a diagnostic, and the ways a tool dies without printing either.
PATTERN='(^|[[:space:]/.:])(fatal )?error: |Command [A-Za-z0-9]+ failed|Terminating app|Abort trap|Segmentation fault|[Ii]llegal instruction'
MAX_LINES=200

# SLF keeps hashes as long hex runs, and a single command can run to kilobytes.
clean() {
  LC_ALL=C sed -E 's/[0-9A-F]{40,}/<hex>/g' | LC_ALL=C cut -c1-800
}

newest="$(ls -t "$@" 2>/dev/null | head -n 1)"
if [ -z "$newest" ] || [ ! -s "$newest" ]; then
  echo "::error title=iOS build::xcodebuild left no activity log, so the reason for the failure was not recorded."
  exit 0
fi

# An .xcactivitylog is gzip around Apple's SLF format, whose strings are plain
# text with CR line endings. That is enough for grep, and unlike a real parser it
# does not depend on the format version. A log cut short still decodes up to the
# cut, so gunzip's complaint about it is not an error here.
decoded="$(mktemp)"
gunzip -c "$newest" 2>/dev/null | LC_ALL=C tr '\r' '\n' >"$decoded"

echo "Activity log: $newest"

if LC_ALL=C grep -a -q -E "$PATTERN" "$decoded"; then
  # The lines above a hit are the output of the command that failed.
  LC_ALL=C grep -a -n -B 6 -E "$PATTERN" "$decoded" | clean | head -n "$MAX_LINES"
  first="$(LC_ALL=C grep -a -m 1 -E "$PATTERN" "$decoded" | clean)"
  echo "::error title=iOS build::${first//%/%25}"
else
  LC_ALL=C tail -n 60 "$decoded" | clean
  echo "::error title=iOS build::No failure line in the activity log. Its last lines are above, and the log is in the ios-build-log artifact."
fi

rm -f "$decoded"
exit 0
