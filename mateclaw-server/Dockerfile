# Multi-stage build
#
# 200.200.4.33 is an isolated test host: every build/runtime base must come
# from Sangfor Harbor and be immutable. Node and Maven already have reviewed
# internal mirrors, so their tag + repository digest is the safe default. The
# Maven and Playwright mirrors below were imported through the controlled
# Jenkins/Harbor path and are pinned to the resulting repository digests. Do
# not fall back to public registries or replace either digest with a mutable tag.
ARG MATECLAW_FRONTEND_BASE_IMAGE=itharbor.sangfor.com/base-image/node:22.18.0-alpine@sha256:ac0d137a585eaaaf648c4f011a6f52cbb71952f9471e76c343fdb2d1cd711b62
ARG MATECLAW_BACKEND_BASE_IMAGE=itharbor.sangfor.com/ai-uat/mateclaw-maven:3.9.6-eclipse-temurin-21-alpine@sha256:1750ed0e15881d6b9e11d8657026a492cd29e85e009481bbb1d0d7a0056e42b9
ARG MATECLAW_RUNTIME_BASE_IMAGE=itharbor.sangfor.com/ai-uat/mateclaw-playwright:v1.62.0-noble@sha256:0e5163ed3364179e474b849dbecfaa46a06e21212abe2c67873f706dc609b88e
#
# Stage 1 — Frontend (Node / npm)
#   Builds the Vue 3 admin SPA and emits static files to /static inside the
#   build container. These files are later copied into the JAR's classpath so
#   Spring Boot serves the SPA at the root URL.
FROM ${MATECLAW_FRONTEND_BASE_IMAGE} AS frontend-builder
WORKDIR /frontend
# Install dependencies first (layer cache)
COPY mateclaw-ui/package.json mateclaw-ui/package-lock.json ./
# Jenkins 200.200.4.33 runs Docker 18.06 with the legacy `overlay` driver.
# pnpm completes every download but the old driver rejects its final metadata
# write with EPERM, including copy-only and hoisted layouts. npm uses a plain
# node_modules layout here; package-lock.json keeps the release graph exact.
RUN npm ci --no-audit --no-fund
# Copy source and build
COPY mateclaw-ui/ ./
# Override outDir: vite.config.ts writes to ../mateclaw-server/…/static which
# is outside this container; call vite directly to control --outDir.
# NODE_OPTIONS=--max-old-space-size=6144 keeps Rollup's `rendering chunks`
# phase from getting SIGKILL'd by the host kernel's OOM-killer on memory-
# constrained servers. The earlier removal of this flag relied on lazy-
# loading + manualChunks dropping the per-chunk peak, but Rollup still
# minifies several vendor chunks (monaco / mermaid / echarts) in parallel
# so the cumulative working set blows past Node's default ~1.5 GB heap
# and trips the OOM-killer mid-build. The fix is not the heap flag
# itself; it is keeping the build reproducible on smaller hosts.
# Skipping vue-tsc here is intentional — type errors are caught in CI, not in
# the production Docker image build.
RUN NODE_OPTIONS=--max-old-space-size=6144 npm exec -- vite build --outDir /static --emptyOutDir

# Stage 2 — Backend (Maven)
# The reviewed internal Maven mirror is pinned by repository digest above.
# Docker 18's runtime assembly still uses the separately reviewed custom
# seccomp path; no unsupported --security-opt is passed to `docker build`.
FROM ${MATECLAW_BACKEND_BASE_IMAGE} AS builder

# Optional Maven extra flags passed at build time.
# Set MAVEN_FLAGS=-Paliyun-first in .env (or via --build-arg) to put Aliyun
# repos first. This speeds up builds inside mainland China.
ARG MAVEN_FLAGS=""

# Keep Docker Maven behavior aligned with the POM repository order. The copied
# settings file deliberately has no forced mirror; MAVEN_FLAGS can opt into the
# existing aliyun-first profile when a builder explicitly needs it.
COPY mateclaw-server/settings.xml /root/.m2/settings.xml

# Copy the root parent plus module POMs first for Docker layer caching.
#
# This list MUST mirror <modules> in the root pom.xml, even for modules this
# image never builds. Maven fails while constructing the reactor if a declared
# module directory is missing ("Child module /build/<name> does not exist"),
# so `-pl mateclaw-server -am` aborts before it ever gets to dependency
# resolution. When a module is added to the root POM, add its pom.xml here too.
WORKDIR /build
COPY pom.xml ./pom.xml
COPY mateclaw-plugin-api/pom.xml mateclaw-plugin-api/pom.xml
COPY mateclaw-server/pom.xml mateclaw-server/pom.xml
COPY mateclaw-plugin-sample/pom.xml mateclaw-plugin-sample/pom.xml
COPY mateclaw-plugin-search-sample/pom.xml mateclaw-plugin-search-sample/pom.xml
COPY mateclaw-plugin-mem0/pom.xml mateclaw-plugin-mem0/pom.xml

# Pre-fetch backend dependencies through the reactor so the parent POM,
# dependencyManagement, and internal module versions all resolve consistently.
RUN mvn -pl mateclaw-server -am dependency:go-offline -q ${MAVEN_FLAGS}

# Copy backend source and inject pre-built frontend into the right classpath location
COPY mateclaw-plugin-api/src mateclaw-plugin-api/src
COPY mateclaw-server/src mateclaw-server/src
COPY --from=frontend-builder /static mateclaw-server/src/main/resources/static

RUN mvn -pl mateclaw-server -am package -Dmaven.test.skip=true -q ${MAVEN_FLAGS}

# Stage 3 — Runtime
#
# Uses the controlled Sangfor Harbor mirror of Microsoft's Playwright image
# (Ubuntu Noble, glibc) with all three browsers (Chromium / Firefox / WebKit)
# and every system library Chromium needs pre-installed. This avoids the
# `playwright install` step and the Alpine/musl incompatibility that blocks
# browser_use on minimal images.
# We pin to the exact Playwright version declared in the root pom.xml. If you
# bump the Java dependency, bump this tag in lockstep — Microsoft rebuilds each
# tag with the matching driver, so mismatched versions cause the java driver to
# re-download browsers at runtime (defeating the whole point of this image).
FROM ${MATECLAW_RUNTIME_BASE_IMAGE}
WORKDIR /app

# Immutable deployment identity. docker-compose passes the clean checkout's
# full Git SHA as a build argument; baking it into the image avoids a mutable
# runtime variable claiming that dirty or older code is the requested release.
ARG MATECLAW_RELEASE_COMMIT=unknown

# Old Docker/legacy-overlay can expose inherited Ubuntu keyrings without read
# permission. Vendor the keyring fetched from Ubuntu's canonical HTTPS endpoint
# as base64, pin its decoded digest, and install it in both Ubuntu keyring paths
# used by supported source formats. Updating this trust root therefore
# requires a deliberate source change and review, while the build remains
# offline-safe.

# JDK 21 is NOT part of the base image (it ships Node for the JS driver).
# Install openjdk-21 explicitly and add CJK fonts so Chinese pages render
# correctly in screenshots and snapshots.
#
# PDF extraction toolchain — DocumentExtractTool tries pdftotext first, then
# Python pdfplumber/pypdf, then falls through to a naive Java parser that
# reads bytes as ISO_8859_1 (mojibake for CJK). Without poppler-utils the
# Docker image always hits the naive path and feeds garbled text to the
# Wiki pipeline.
#
# We install poppler-utils (backend 1) and tesseract (backend 4), which
# together cover the vast majority of PDFs including scanned docs. The
# Python backend is intentionally skipped — pip install against aliyun
# mirrors in CN networks hits transient hash-mismatch failures on cffi /
# cryptography transitive deps, and RFC-051 PR-1c will replace the Python
# hop with JVM-native Tika extraction anyway. Leaving it out keeps the
# image ~200 MB smaller and the build reproducible.
# The Playwright image carries NodeSource only to install the Node runtime it
# already contains. Keeping that repository enabled makes every later APT
# operation depend on an unrelated third-party signing key. Remove it before
# installing our runtime packages. Jenkins' Docker 18.06 + legacy overlay has
# also exposed inherited keyrings without read permission for the `_apt` user;
# restore the pinned Ubuntu archive keyring and its read access explicitly
# instead of disabling signature verification. The keyring is extracted from
# Ubuntu's official ubuntu-keyring_2026.08.18_all.deb package (package SHA-256
# fec10bd81d9ce809a5c11c6227a367611dd0e2589afebb41d46aefb350be8f40),
# which carries the renewed 2018 archive signing certificate required after
# the older certificate expired in 2026.
COPY mateclaw-server/docker/ubuntu-archive-keyring.gpg.b64 /tmp/ubuntu-archive-keyring.gpg.b64
COPY mateclaw-server/docker/install-runtime-dependencies.sh /usr/local/sbin/mateclaw-install-runtime-dependencies
RUN /usr/local/sbin/mateclaw-install-runtime-dependencies /tmp/ubuntu-archive-keyring.gpg.b64 \
    && rm -f /tmp/ubuntu-archive-keyring.gpg.b64

# Tell Playwright Java where Microsoft's image stored the browsers.
# BrowserLauncher's BUNDLED strategy will then succeed without extra config.
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    MATECLAW_RELEASE_COMMIT=${MATECLAW_RELEASE_COMMIT} \
    JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Shanghai -Dsun.jnu.encoding=UTF-8"

# Default DB profile, overridable by the SPRING_PROFILES_ACTIVE env var
# (compose sets it explicitly: mysql / postgres / kingbase). It must be an ENV,
# not a -D system property on the ENTRYPOINT: a hardcoded
# -Dspring.profiles.active outranks the SPRING_PROFILES_ACTIVE env var and would
# silently pin the profile regardless of what compose passes.
ENV SPRING_PROFILES_ACTIVE=mysql

COPY --from=builder /build/mateclaw-server/target/*.jar app.jar

EXPOSE 18088
EXPOSE 1455
USER 0:0
ENTRYPOINT ["java", "-jar", "app.jar"]
CMD []
