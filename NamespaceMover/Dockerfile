FROM rider-plugin-base AS builder

WORKDIR /project

COPY build.gradle.kts settings.gradle.kts ./
RUN gradle dependencies --no-daemon 2>/dev/null || true

COPY src/ src/
RUN gradle buildPlugin --no-daemon

FROM alpine:3.19 AS output
WORKDIR /output
COPY --from=builder /project/build/distributions/*.zip ./
