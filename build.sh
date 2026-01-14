#!/bin/bash
# File: build.sh

set -e

echo "Building Observability Service..."

# Clean and build
mvn clean package -DskipTests

echo "Build completed successfully!"
echo "JAR file: target/observability-service-1.0.0.jar"