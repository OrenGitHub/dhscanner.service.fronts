# Tiny Java front for dhscanner: zero external runtime dependencies.
#
# Everything parsing-related comes from javac's public tree API
# (`com.sun.source.*`) which lives in the JDK's `jdk.compiler` module;
# HTTP uses the built-in `com.sun.net.httpserver.*` from `jdk.httpserver`.
# Because of that, the whole runtime fits in a jlink-stripped custom
# image (~80 MB final size, cold-start well under a second), which is
# in the same spirit as:
#
#   - the Go front (`golang:1.20-alpine` -> `alpine:latest` ~50 MB)
#   - the PHP front (just `php -S`, no framework)
#   - the Python front (`python:3.12-slim` + FastAPI)
#
# We deliberately avoid Maven / Gradle and any HTTP framework (Spring /
# Micronaut / Javalin / ...): there are no dependencies to resolve, so a
# plain `javac` + `jar` call is simpler, faster to build, and keeps the
# image free of a 20+ MB build-tool cache layer.

# -------- Build stage --------
# Java 25 is the current LTS release (Sep 2025); upgrade in lockstep
# with the LTS cadence, same way the C# front sits on the `.NET 8` LTS.
# If `eclipse-temurin:25-jdk-alpine` ever stops being published for the
# host arch, the drop-in alternatives are `eclipse-temurin:25-jdk` (Ubuntu-
# based, ~400 MB final image) or `amazoncorretto:25-alpine`.
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /app

COPY src ./src

# Compile every .java file into out/, then package into a single
# executable jar. The `src/main/java/...` layout is kept purely as a
# convention (nothing in the toolchain enforces it); `javac` is happy
# with any directory tree as long as package declarations match.
RUN mkdir -p out \
 && find src -name '*.java' -print0 | xargs -0 javac -d out \
 && jar --create --file=app.jar --main-class=dhscanner.front.java.App -C out .

# Build a minimal JRE containing ONLY the modules we actually import.
# `--add-modules` pulls in transitive requires automatically, so listing
# `jdk.compiler,jdk.httpserver` is enough to also drag in `java.base`,
# `java.compiler`, `java.logging`, etc.
#
# Flags:
#   --strip-debug         drop LineNumberTable / LocalVariableTable from
#                         every bundled class; shaves ~15 MB off the
#                         JRE, has zero impact on runtime behavior.
#   --no-man-pages        -5 MB (we don't ship a shell)
#   --no-header-files     -5 MB (we don't ship a C compiler)
#   --compress=zip-9      compresses modules.jar with max zip level;
#                         ~15% size win, ~1 ms extra class load, net
#                         win for a cold-start-sensitive service.
RUN jlink \
      --add-modules jdk.compiler,jdk.httpserver \
      --strip-debug \
      --no-man-pages \
      --no-header-files \
      --compress=zip-9 \
      --output /jre

# -------- Runtime stage --------
# `alpine:3` + the stripped jlink runtime + our app.jar is the whole
# image. Because the builder stage was `eclipse-temurin:25-jdk-alpine`
# (musl libc), the jlink output is musl-linked and runs cleanly on a
# vanilla alpine base -- no need to carry a full JDK into production.
FROM alpine:3
WORKDIR /app

COPY --from=builder /jre     /jre
COPY --from=builder /app/app.jar ./app.jar

ENV PATH="/jre/bin:${PATH}"
EXPOSE 5000
CMD ["/jre/bin/java", "-jar", "/app/app.jar"]
