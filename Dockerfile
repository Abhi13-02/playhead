# Packages a jar that is already built on the host, rather than building inside the image.
#
# This started as a two-stage build (full JDK compiles, JRE-only image ships the result) so the
# image that runs never carries Gradle, the source tree, or a compiler it never uses. That build
# stage needs the Gradle wrapper to download its own distribution over HTTPS on first run, and on
# this machine that step began timing out consistently inside Docker's build network specifically
# — `docker run` containers could reach the same host fine, only the builder's network path could
# not, even after raising the wrapper's timeout and retry count. Real, current, environment-level
# flakiness, not a project bug.
#
# The practical fix: build with `./gradlew bootJar` on the host (already the normal workflow all
# session, and reliable), and have the image just package the result. This is a standard pattern —
# many real CI pipelines build the artifact in one tool/stage and COPY it into a slim runtime image
# — and it still keeps the shipped image free of build tooling; only the *place* the build runs
# moved. Run `./gradlew bootJar` before `docker compose build app`.

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# curl is here only so Compose's healthcheck has something to call. The scaling controller uses
# that healthcheck to know when a new replica is actually serving, which is the measured number
# the pre-scale lead time is built on (NFR-6).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# The Postgres JDBC driver sends the JVM's timezone to the server on connect, and the JVM picks up
# the host's. A Windows host reports "Asia/Calcutta", which Postgres rejects outright — the same
# failure D-021 pinned the bootRun jvmArg for. Setting it in the image means the container is
# correct regardless of which machine builds or runs it.
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"

COPY build/libs/playhead.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
