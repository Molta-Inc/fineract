# Multi-stage build for Apache Fineract
FROM gradle:8.10-jdk21 AS builder

WORKDIR /opt/fineract

COPY . .

# Build the application as a fat jar
RUN ./gradlew clean bootJar -x test

FROM eclipse-temurin:21-jre-jammy

WORKDIR /opt/fineract

# Copy the built fat jar
COPY --from=builder /opt/fineract/fineract-provider/build/libs/fineract-provider*.jar ./fineract-provider.jar

# Create logs directory
RUN mkdir -p /opt/fineract/logs

# Let Docker Compose handle environment variables → no ENV declarations here

# Entrypoint
ENTRYPOINT ["java", "-jar", "fineract-provider.jar"]

EXPOSE 8443