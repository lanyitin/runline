# Workspace image: JDK 25 base plus a pinned Node toolchain (build-time only; never part of a release artifact).
# The Node version comes from /.node-version, the single source shared with mise (see docs/stable/dev-environment.md).
FROM mcr.microsoft.com/devcontainers/java:25-bookworm

COPY .node-version /tmp/.node-version

# Install the exact Node version from nodejs.org and verify it against the published SHASUMS256.txt.
RUN set -eu; \
    NODE_VERSION="$(tr -d '[:space:]' < /tmp/.node-version)"; \
    case "$(dpkg --print-architecture)" in \
      amd64) NODE_ARCH=x64 ;; \
      arm64) NODE_ARCH=arm64 ;; \
      *) echo "Unsupported architecture for Node" >&2; exit 1 ;; \
    esac; \
    FILE="node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz"; \
    cd /tmp; \
    curl -fsSLO "https://nodejs.org/dist/v${NODE_VERSION}/${FILE}"; \
    curl -fsSLO "https://nodejs.org/dist/v${NODE_VERSION}/SHASUMS256.txt"; \
    grep " ${FILE}\$" SHASUMS256.txt | sha256sum -c -; \
    tar -xJf "${FILE}" -C /usr/local --strip-components=1 --no-same-owner; \
    rm -f "${FILE}" SHASUMS256.txt /tmp/.node-version; \
    node --version; npm --version
