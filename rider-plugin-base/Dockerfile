# ═══════════════════════════════════════════════════════
#  rider-plugin-base
#  Base image with Gradle 8.13 + Rider 2025.2.1 SDK
#  cached inside the image.
#
#  Build once:
#    docker build -t rider-plugin-base .
#
#  Then in your plugin's Dockerfile use:
#    FROM rider-plugin-base AS builder
# ═══════════════════════════════════════════════════════
FROM eclipse-temurin:21-jdk

WORKDIR /base

# ── 1. Gradle wrapper ───────────────────────────────────
RUN mkdir -p gradle/wrapper
COPY gradle-wrapper.properties gradle/wrapper/gradle-wrapper.properties
COPY gradlew .
RUN chmod +x gradlew

# Download Gradle distribution
RUN ./gradlew --version --no-daemon

# ── 2. Minimal build files to pull Rider SDK ───────────
COPY build.gradle.kts .
COPY settings.gradle.kts .

# Resolve all dependencies (downloads Rider SDK ~2 GB into
# the Gradle cache inside this image layer)
RUN ./gradlew dependencies --no-daemon 2>&1 | tail -5 || true

# Pre-warm the Kotlin/IntelliJ plugin compilation cache
RUN ./gradlew compileKotlin --no-daemon 2>/dev/null || true

WORKDIR /project
