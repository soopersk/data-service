# ============================================================================
# Multi-stage Dockerfile — Spring Boot Microservice
# ============================================================================
# Stage 1: Extract layered JAR for optimal Docker caching
# Stage 2: Minimal runtime image with non-root user
# ============================================================================

# --- Stage 1: Extract layers ---
FROM eclipse-temurin:17-jre-jammy AS layers
WORKDIR /app
COPY target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# --- Stage 2: Runtime ---
FROM eclipse-temurin:17-jre-jammy

# Security: run as non-root
RUN groupadd --system appuser && \
    useradd --system --gid appuser --shell /bin/false appuser

WORKDIR /app

# Copy layers in order of change frequency (least → most)
COPY --from=layers /app/dependencies/ ./
COPY --from=layers /app/spring-boot-loader/ ./
COPY --from=layers /app/snapshot-dependencies/ ./
COPY --from=layers /app/application/ ./

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1

# Metadata
LABEL maintainer="team@yourcompany.com"
LABEL org.opencontainers.image.source="https://gitlab.yourcompany.com/your-group/my-microservice"

# Switch to non-root user
USER appuser

EXPOSE 8080

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
