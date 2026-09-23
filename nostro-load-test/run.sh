#!/usr/bin/env bash
# One load-test run against the compose stack, and everything the results note is written from:
# Gatling's report, the outcome counts, the API's metrics before and after, and a JFR summary.
#
#   nostro-load-test/run.sh [-Dusers=32] [-DarmSeconds=60] [-DspreadAccounts=64]
#
# Needs Docker and a JDK 21 on the PATH (for `jfr`). Never run by CI.
set -euo pipefail
cd "$(dirname "$0")/.."

out=nostro-load-test/target/load-results
jfr_dir=nostro-load-test/target/jfr
mkdir -p "$out" "$jfr_dir"
rm -f "$jfr_dir/api.jfr"
compose=(docker compose -f compose.yaml -f nostro-load-test/compose.load.yaml)

"${compose[@]}" up -d --build --wait --wait-timeout 300
# A fresh API, so the recording covers this run.
"${compose[@]}" up -d --force-recreate --wait --wait-timeout 300 api

scrape() { "${compose[@]}" exec -T api curl -fs localhost:8081/actuator/prometheus; }
scrape > "$out/metrics-before.txt"

./mvnw -B -f nostro-load-test/pom.xml gatling:test -Doutcomes="$out/outcomes.tsv" "$@" | tee "$out/gatling.log"

scrape > "$out/metrics-after.txt"
# Stopping the service is what writes the recording (dumponexit).
"${compose[@]}" stop api
# Moved out before the service restarts, which would start a new recording at the same path.
mv "$jfr_dir/api.jfr" "$out/api.jfr"
jfr summary "$out/api.jfr" > "$out/jfr-summary.txt"
for view in hot-methods gc-pauses gc allocation-by-class contention-by-site thread-cpu-load; do
  jfr view --width 160 "$view" "$out/api.jfr" > "$out/jfr-$view.txt" 2>&1 || true
done
"${compose[@]}" up -d --wait --wait-timeout 300 api
echo "results in $out"
