# The fixed build platform of the release build (WI-32, ADR-016): the one place where a release is
# guaranteed to be byte-reproducible. Used by `./gradlew :engine:verifyReproducibleRelease`, which
# builds it for linux/amd64 and passes NODE_VERSION from .node-version.
#
# - JDK: Eclipse Temurin 25 (the project's toolchain), the multi-architecture index pinned by
#   digest; the linux/amd64 image is what the platform flag selects from it. To move to a newer
#   JDK build, change the digest on purpose: `docker buildx imagetools inspect
#   eclipse-temurin:25-jdk-noble`.
# - Node: exactly NODE_VERSION, from nodejs.org, verified against the published SHASUMS256.txt.
# - git: to clone the commit that is built. No other tool matters for the bytes of the jars.
FROM eclipse-temurin:25-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4

ARG NODE_VERSION
RUN set -eu; \
    test -n "$NODE_VERSION"; \
    apt-get update; \
    apt-get install -y --no-install-recommends git xz-utils curl ca-certificates; \
    rm -rf /var/lib/apt/lists/*; \
    FILE="node-v${NODE_VERSION}-linux-x64.tar.xz"; \
    cd /tmp; \
    curl -fsSLO "https://nodejs.org/dist/v${NODE_VERSION}/${FILE}"; \
    curl -fsSLO "https://nodejs.org/dist/v${NODE_VERSION}/SHASUMS256.txt"; \
    grep " ${FILE}\$" SHASUMS256.txt | sha256sum -c -; \
    tar -xJf "${FILE}" -C /usr/local --strip-components=1 --no-same-owner; \
    rm -f "${FILE}" SHASUMS256.txt; \
    node --version; npm --version

# What the builds run with: the same everywhere, nothing of the machine that started the container.
ENV TZ=UTC LANG=C.UTF-8 LC_ALL=C.UTF-8
