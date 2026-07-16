#!/usr/bin/env bash
# Unit tests for the shared library's plain-Groovy classes.
#
# No Jenkins required: DeployConfig receives the script object by constructor,
# so a stub is sufficient. Runs in a container so the toolchain is pinned and
# no local Groovy install is needed.
set -euo pipefail
cd "$(dirname "$0")/.."

docker run --rm -v "$(pwd)":/w -w /w groovy:4.0-jdk17 \
  groovy -cp src test/DeployConfigTest.groovy
