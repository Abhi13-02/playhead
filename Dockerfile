# Two-stage build. Stage one compiles with the full JDK; stage two ships only the runtime and the
# built jar, so the image that actually runs does not carry Gradle, the source tree, or a compiler
# it will never use.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# Wrapper and build scripts first, on their own layer. They change far less often than the source,
# so Docker reuses the cached dependency download on every build where only code changed.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

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

COPY --from=build /src/build/libs/playhead.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
