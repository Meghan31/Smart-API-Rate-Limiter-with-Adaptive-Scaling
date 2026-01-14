# ============================================
# Multi-Stage Build for Smart API Rate Limiter
# ============================================
# This Dockerfile uses a multi-stage build to create a minimal production image
# Stage 1: Build the application
# Stage 2: Create the production runtime image
# Final image size target: < 250MB

# ============================================
# Stage 1: Build Stage
# ============================================
FROM maven:3.9-eclipse-temurin-21 AS builder

# Set working directory
WORKDIR /app

# Copy pom.xml first for better layer caching
# This allows Docker to cache dependencies if pom.xml hasn't changed
COPY pom.xml .

# Download dependencies (this layer will be cached if pom.xml doesn't change)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
# -DskipTests: Skip tests during Docker build (run them in CI/CD pipeline)
# -B: Batch mode (non-interactive)
RUN mvn clean package -DskipTests -B

# ============================================
# Stage 2: Production Runtime Stage
# ============================================
FROM eclipse-temurin:21-jre-alpine AS production

# Install curl for healthcheck
RUN apk add --no-cache curl

# Create a non-root user for security
# Running as non-root is a security best practice
RUN addgroup -S spring && adduser -S spring -G spring

# Set working directory
WORKDIR /app

# Copy the JAR file from builder stage
# The JAR file is named smart-rate-limiter-0.0.1-SNAPSHOT.jar based on pom.xml
COPY --from=builder /app/target/smart-rate-limiter-0.0.1-SNAPSHOT.jar app.jar

# Change ownership to spring user
RUN chown -R spring:spring /app

# Switch to non-root user
USER spring:spring

# Expose application port
EXPOSE 8080

# Add healthcheck
# This allows Docker to monitor the application health
# --interval: Time between health checks
# --timeout: Maximum time to wait for health check
# --start-period: Grace period for application startup
# --retries: Number of consecutive failures before marking unhealthy
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# JVM tuning options for container environment
# -XX:+UseContainerSupport: Enable container-aware JVM settings
# -XX:MaxRAMPercentage=75.0: Use 75% of available RAM for heap
# -XX:+ExitOnOutOfMemoryError: Exit on OOM instead of hanging
# -Djava.security.egd: Use non-blocking random for faster startup
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.security.egd=file:/dev/./urandom"

# Run the application
# $JAVA_OPTS allows runtime JVM customization
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
