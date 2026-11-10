# syntax=docker/dockerfile:1
# Quad server image. Build from the repo root:
#   docker build -t campus-market-server .
#
# The Gradle build configures every module of the repo, including the Android library plugin that :core uses,
# which insists on an Android SDK directory even when only :server tasks run. An empty SDK dir is enough
# for `:server:installDist` (nothing Android is compiled), so we point `sdk.dir` at one.

# ---- build stage ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
ENV ANDROID_HOME=/opt/android-sdk
RUN mkdir -p "$ANDROID_HOME" && echo "sdk.dir=$ANDROID_HOME" > local.properties

# Copy build scripts first so dependency downloads are cached when only sources change.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY core/build.gradle.kts core/
COPY shared/build.gradle.kts shared/
COPY server/build.gradle.kts server/
COPY androidApp/build.gradle.kts androidApp/
COPY desktopApp/build.gradle.kts desktopApp/
RUN ./gradlew --no-daemon --console=plain help -q || true

COPY core ./core
COPY server ./server
RUN ./gradlew --no-daemon --console=plain :server:installDist -x test

# ---- runtime stage ----
FROM eclipse-temurin:17-jre
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app \
 && mkdir -p /app /data/uploads \
 && chown -R app:app /app /data
WORKDIR /app
COPY --from=build --chown=app:app /src/server/build/install/server/ /app/

ENV PORT=8080 \
    UPLOADS_DIR=/data/uploads \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
VOLUME ["/data/uploads"]
EXPOSE 8080
USER app
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT}; printf 'GET /api/v1/health HTTP/1.0\\r\\n\\r\\n' >&3; grep -q '200 OK' <&3"]
ENTRYPOINT ["/app/bin/server"]
