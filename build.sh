#!/usr/bin/env bash
# Build lokal WoodSeller (butuh JDK 21 + Maven + internet)
set -e
mvn -B clean package
jar tf target/WoodSeller-1.0.0.jar | grep -E "^(plugin.yml|config.yml|id/regaya/woodseller/WoodSeller.class)$"
echo "Jar siap: target/WoodSeller-1.0.0.jar"
