FROM ubuntu:26.04

ENV DEBIAN_FRONTEND=noninteractive
# Every JDK the CI matrix builds against, so any failing leg can be reproduced
# locally with `JAVA_HOME=/usr/lib/jvm/temurin-<v>-jdk-amd64 mvn verify`.
# Keep in sync with .github/workflows/ci.yml.
ARG JAVA_VERSIONS="17 21 25"
# Default toolchain; matches the leg that produces the coverage badge.
ARG JAVA_VERSION=21
ENV JAVA_HOME=/usr/lib/jvm/temurin-${JAVA_VERSION}-jdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    git \
    build-essential \
    gnupg \
    && rm -rf /var/lib/apt/lists/*

# Temurin JDK (Adoptium) + Maven + Gradle
RUN curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
       | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release; echo $VERSION_CODENAME) main" \
       > /etc/apt/sources.list.d/adoptium.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends maven gradle \
       $(for v in ${JAVA_VERSIONS}; do echo "temurin-$v-jdk"; done) \
    && update-alternatives --set java ${JAVA_HOME}/bin/java \
    && update-alternatives --set javac ${JAVA_HOME}/bin/javac \
    && rm -rf /var/lib/apt/lists/*

# Node.js (LTS) for Claude Code
RUN curl -fsSL https://deb.nodesource.com/setup_lts.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# Claude Code
RUN npm install -g @anthropic-ai/claude-code

# GitHub CLI (gh) — installed from GitHub's official apt repository
RUN mkdir -p -m 755 /etc/apt/keyrings \
    && curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
       -o /etc/apt/keyrings/githubcli-archive-keyring.gpg \
    && chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
       > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends gh \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

CMD ["/bin/bash"]
