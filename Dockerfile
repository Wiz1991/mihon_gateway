# Build stage
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

# Copy gradle wrapper and build files
COPY gradle ./gradle
COPY gradlew gradlew.bat ./
COPY build.gradle.kts settings.gradle.kts ./
COPY AndroidCompat/build.gradle.kts ./AndroidCompat/
COPY AndroidCompat/Config/build.gradle.kts ./AndroidCompat/Config/

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon || true

# Copy only source code (not build artifacts)
COPY src ./src
COPY AndroidCompat/src ./AndroidCompat/src
COPY AndroidCompat/Config/src ./AndroidCompat/Config/src
COPY AndroidCompat/getAndroid.sh AndroidCompat/getAndroid.ps1 ./AndroidCompat/

# Build the application with minimal output
RUN ./gradlew installDist --no-daemon --stacktrace && \
    # Clean up gradle cache to reduce layer size
    rm -rf /root/.gradle/caches/*/scripts* \
           /root/.gradle/caches/*/fileHashes \
           /root/.gradle/caches/*/javaCompile

# Runtime stage - use alpine for minimal size (~200MB total vs 900MB+)
FROM eclipse-temurin:21-jre-alpine

# Create non-root user
RUN addgroup -S mihon && \
    adduser -S -G mihon -h /home/mihon mihon

WORKDIR /app

# Split lib directory into multiple layers to stay under Cloudflare's 100-500MB request limit
# Each layer will be ~40-50MB compressed, well under limits

# Layer 1: Largest JARs (js-language, android, icu4j, truffle)
COPY --from=builder --chown=mihon:mihon /build/build/install/mihon_gateway/lib/js-language*.jar \
                                          /build/build/install/mihon_gateway/lib/android-jar*.jar \
                                          /build/build/install/mihon_gateway/lib/icu4j*.jar \
                                          /build/build/install/mihon_gateway/lib/truffle*.jar \
                                          ./lib/

# Layer 2: Large JARs (AndroidCompat, grpc, regex, kotlin-reflect)
COPY --from=builder --chown=mihon:mihon /build/build/install/mihon_gateway/lib/AndroidCompat*.jar \
                                          /build/build/install/mihon_gateway/lib/grpc-*.jar \
                                          /build/build/install/mihon_gateway/lib/regex*.jar \
                                          /build/build/install/mihon_gateway/lib/kotlin-reflect*.jar \
                                          /build/build/install/mihon_gateway/lib/webp*.jar \
                                          ./lib/

# Layer 3: Medium JARs (guava, proto, protobuf, kotlin-stdlib, kotlinx)
COPY --from=builder --chown=mihon:mihon /build/build/install/mihon_gateway/lib/guava*.jar \
                                          /build/build/install/mihon_gateway/lib/proto*.jar \
                                          /build/build/install/mihon_gateway/lib/jna*.jar \
                                          /build/build/install/mihon_gateway/lib/kotlin-stdlib*.jar \
                                          /build/build/install/mihon_gateway/lib/kotlinx-*.jar \
                                          /build/build/install/mihon_gateway/lib/rxjava*.jar \
                                          ./lib/

# Layer 4: All remaining smaller JARs
COPY --from=builder --chown=mihon:mihon /build/build/install/mihon_gateway/lib/*.jar ./lib/

# Copy bin directory (scripts - small)
COPY --from=builder --chown=mihon:mihon /build/build/install/mihon_gateway/bin ./bin

# Create data directory
RUN mkdir -p /home/mihon/.local/share/mihon_gateway && \
    chown -R mihon:mihon /home/mihon

# Switch to non-root user
USER mihon

# Expose gRPC port (default 50051)
EXPOSE 50051

# Environment variables
ENV DATA_DIR=/home/mihon/.local/share/mihon_gateway \
    GRPC_PORT=50051 \
    JAVA_OPTS="-Xmx512m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC"

# Set data directory as volume
VOLUME ["/home/mihon/.local/share/mihon_gateway"]

# Run the application
CMD ["sh", "-c", "exec ./bin/mihon_gateway $JAVA_OPTS"]
