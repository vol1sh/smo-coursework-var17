#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
javac --release 17 -encoding UTF-8 -d out src/main/java/smo/*.java src/test/java/smo/*.java
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -cp out smo.SimulationSelfTest
