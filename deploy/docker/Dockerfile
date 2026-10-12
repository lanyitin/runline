# The Engine as deployed (docs/stable/pipeline-engine/04-deployment-docker.md).
#
# The build context is the output of `./gradlew :engine:engineDistribution`
# (engine/build/engine-dist: engine.jar and run-runtime/). The image only packs that artifact; it
# builds nothing, and Node and build tools are not in it. No secret and no configuration is baked in:
# everything comes from environment variables when a container starts.
#
# This file has these stages: `engine` (below), `browser` (for pipelines that drive a browser) and
# `transcribe` (for the transcribe pipeline) and `browser-transcribe` (both; the last one). The last stage is what a build without
# --target makes, so name the one you want:
#
#   docker build --target engine -f deploy/docker/Dockerfile -t runline-engine engine/build/engine-dist
# Pinned by the hash of the multi-platform index of eclipse-temurin:25-jre (ADR-032). This line is the one
# definition: deploy/publish-image reads it for its architecture check. Update: deploy/browser.md.
FROM eclipse-temurin:25-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636 AS engine

# A dedicated unprivileged account. The three directories are the places the Engine writes to: the
# shared root (ADR-009) and the resource root (the files of `file` shared resources, ADR-019) must
# be volumes, the run root is scratch space. The three are siblings: none may be inside another.
RUN useradd --system --uid 10001 --home-dir /nonexistent --shell /usr/sbin/nologin runline \
    && mkdir -p /var/lib/runline/shared /var/lib/runline/runs /var/lib/runline/resources \
    && chown -R runline:runline /var/lib/runline

# engine.jar and run-runtime/ stay two separate parts (ADR-001): run-runtime/ must not hold Engine
# classes.
COPY --chown=root:root engine.jar /app/engine.jar
COPY --chown=root:root run-runtime/ /app/run-runtime/

ENV RUNLINE_RUNTIME_DIR=/app/run-runtime \
    RUNLINE_SHARED_ROOT=/var/lib/runline/shared \
    RUNLINE_RUN_ROOT=/var/lib/runline/runs \
    RUNLINE_RESOURCE_ROOT=/var/lib/runline/resources \
    PORT=8080

USER runline
EXPOSE 8080
VOLUME /var/lib/runline/shared /var/lib/runline/resources

# The JVM is the main process (PID 1, so it gets SIGTERM itself); exec form, no shell. The
# migration and the health check use the same engine.jar with another main class (04 "Deployment
# entry points").
ENTRYPOINT ["java", "-jar", "/app/engine.jar"]

# The Engine for pipelines that drive a browser (WI-1001, docs/stable/digest-rss/04-deployment.md):
# the same image plus a real Google Chrome, a virtual display and the scripts that start them.
# Google ships Chrome for linux/amd64 only, so this stage builds only for that platform (on an arm64
# host the Docker VM must emulate amd64 with Rosetta). Chrome is installed from Google's package at
# the version given, which is not part of the byte-reproducible release (WI-32): the release is the
# jars, not the image.
#
#   docker build --target browser --platform linux/amd64 --build-context deploy=deploy \
#       -f deploy/docker/Dockerfile -t runline-engine-browser engine/build/engine-dist
FROM engine AS browser
ARG CHROME_VERSION=155.0.8059.39-1
USER root
RUN [ "$(dpkg --print-architecture)" = "amd64" ] \
    || { echo "the browser image is linux/amd64 only (Google Chrome has no other build); build with --platform linux/amd64" >&2; exit 1; }
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl xvfb xauth procps fonts-noto-cjk fonts-liberation \
    && curl -fsSL -o /tmp/chrome.deb "https://dl.google.com/linux/chrome/deb/pool/main/g/google-chrome-stable/google-chrome-stable_${CHROME_VERSION}_amd64.deb" \
    && apt-get install -y --no-install-recommends /tmp/chrome.deb \
    && rm -rf /tmp/chrome.deb /var/lib/apt/lists/*
# Chrome keeps small files in the account's home (its certificate database, desktop settings); the
# account of the plain image has none. Nothing there needs to outlive the container.
RUN mkdir -p /home/runline && chown runline:runline /home/runline
# The directory Xvfb puts its socket in, owned by root and sticky as X wants it, so that Xvfb does not
# have to make it as the unprivileged account.
RUN mkdir -p -m 1777 /tmp/.X11-unix
COPY --from=deploy --chmod=0755 bin/runline-entrypoint bin/runline-browser-probe bin/runline-build-truststore /usr/local/bin/

# Where Chrome is and how it is started are configuration of the pipeline, read from these variables:
# CHROME_ARGS carries what a container needs (no sandbox, no /dev/shm) and the flag without which
# Chrome has no WebGL on a machine without a GPU. Replace any of them in the environment.
ENV CHROME_BIN=/usr/bin/google-chrome-stable \
    CHROME_ARGS="--no-sandbox --disable-dev-shm-usage --ignore-gpu-blocklist" \
    DISPLAY=:99 \
    HOME=/home/runline
USER runline
ENTRYPOINT ["/usr/local/bin/runline-entrypoint"]
CMD ["java", "-jar", "/app/engine.jar"]

# The Engine for the transcribe pipeline (WI-1402, docs/stable/transcribe-pipeline/04-deployment.md):
# the same image plus ffmpeg and ffprobe, the silero-vad model and the places for the VAD tool. No Python.
# The inputs are fetched in a throw-away stage by deploy/bin/runline-transcribe-fetch, which holds their
# versions and sha256 hashes (ADR-032): a changed download fails the build. The VAD tool's jar is not
# here: it is delivered by WI-1407 and mounted (deploy/transcribe.md).
#
#   docker build --target transcribe --build-context deploy=deploy \
#       -f deploy/docker/Dockerfile -t runline-engine-transcribe engine/build/engine-dist
FROM engine AS transcribe-fetch
ARG TARGETARCH
USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl xz-utils \
    && rm -rf /var/lib/apt/lists/*
COPY --from=deploy --chmod=0755 bin/runline-transcribe-fetch /usr/local/bin/
RUN runline-transcribe-fetch /tmp/transcribe "$TARGETARCH"

FROM engine AS transcribe
USER root
# /opt/runline/vad is where the jar of the VAD tool is mounted (read-only); root owns everything here,
# so the Engine account can read and run it and cannot change it. The directories are made before the
# copies: --chmod on a copy into a missing directory would give the directory the file's mode.
RUN mkdir -p -m 0755 /opt/runline/models /opt/runline/vad
COPY --from=transcribe-fetch --chmod=0755 /tmp/transcribe/ffmpeg /tmp/transcribe/ffprobe /usr/local/bin/
COPY --from=transcribe-fetch --chmod=0644 /tmp/transcribe/silero_vad.onnx /tmp/transcribe/silero_vad.onnx.sha256 /opt/runline/models/
COPY --from=deploy --chmod=0755 bin/runline-vad bin/runline-transcribe-check /usr/local/bin/
# The model and the tool are configuration of the pipeline's host, read from these variables.
ENV RUNLINE_VAD_MODEL=/opt/runline/models/silero_vad.onnx \
    RUNLINE_VAD_JAR=/opt/runline/vad/runline-vad.jar
USER runline

# The Engine for pipelines that drive a browser and the transcribe pipeline in one process (WI-1416): the
# `browser` stage plus what the `transcribe` stage adds, copied from it, so that nothing of the transcribe
# inputs (versions, hashes, places) is written a second time. It is linux/amd64 only, as `browser` is.
#
#   docker build --target browser-transcribe --platform linux/amd64 --build-context deploy=deploy \
#       -f deploy/docker/Dockerfile -t runline-engine-browser-transcribe engine/build/engine-dist
FROM browser AS browser-transcribe
USER root
COPY --from=transcribe /opt/runline/ /opt/runline/
COPY --from=transcribe /usr/local/bin/ffmpeg /usr/local/bin/ffprobe /usr/local/bin/runline-vad /usr/local/bin/runline-transcribe-check /usr/local/bin/
ENV RUNLINE_VAD_MODEL=/opt/runline/models/silero_vad.onnx \
    RUNLINE_VAD_JAR=/opt/runline/vad/runline-vad.jar
USER runline
