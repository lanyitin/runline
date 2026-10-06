# The Engine as deployed (docs/stable/pipeline-engine/04-deployment-docker.md).
#
# The build context is the output of `./gradlew :engine:engineDistribution`
# (engine/build/engine-dist: engine.jar and run-runtime/). The image only packs that artifact; it
# builds nothing, and Node and build tools are not in it. No secret and no configuration is baked in:
# everything comes from environment variables when a container starts.
#
#   docker build -f deploy/docker/Dockerfile -t runline-engine engine/build/engine-dist
FROM eclipse-temurin:25-jre

# A dedicated unprivileged account. The two directories are the places the Engine writes to: the
# shared root must be a volume (ADR-009), the run root is scratch space.
RUN useradd --system --uid 10001 --home-dir /nonexistent --shell /usr/sbin/nologin runline \
    && mkdir -p /var/lib/runline/shared /var/lib/runline/runs \
    && chown -R runline:runline /var/lib/runline

# engine.jar and run-runtime/ stay two separate parts (ADR-001): run-runtime/ must not hold Engine
# classes.
COPY --chown=root:root engine.jar /app/engine.jar
COPY --chown=root:root run-runtime/ /app/run-runtime/

ENV RUNLINE_RUNTIME_DIR=/app/run-runtime \
    RUNLINE_SHARED_ROOT=/var/lib/runline/shared \
    RUNLINE_RUN_ROOT=/var/lib/runline/runs \
    PORT=8080

USER runline
EXPOSE 8080
VOLUME /var/lib/runline/shared

# The JVM is the main process (PID 1, so it gets SIGTERM itself); exec form, no shell. The
# migration and the health check use the same engine.jar with another main class (04 "Deployment
# entry points").
ENTRYPOINT ["java", "-jar", "/app/engine.jar"]
