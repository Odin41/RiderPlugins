# ── Uses pre-built base image with Gradle + Rider SDK cached ──────
# Build base first: cd ..\rider-plugin-base && build-base.bat
# ─────────────────────────────────────────────────────────────────
FROM rider-plugin-base AS builder

WORKDIR /project

COPY build.gradle.kts settings.gradle.kts ./

# Resolve any dependency deltas (instant if nothing changed)
RUN gradle dependencies --no-daemon 2>/dev/null || true

# Copy source and build
COPY src/ src/
RUN gradle buildPlugin --no-daemon

# ── Output ────────────────────────────────────────────────────────
FROM alpine:3.19 AS output
WORKDIR /output
COPY --from=builder /project/build/distributions/*.zip ./
