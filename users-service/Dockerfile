FROM gradle:8.8-jdk17 AS builder
WORKDIR /app
COPY . .
# Skip tests to avoid running Testcontainers in builder
RUN gradle --no-daemon clean installDist -x test

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=builder /app/build/install/users-service /opt/app
EXPOSE 8081
ENV PORT=8081
ENTRYPOINT ["/opt/app/bin/users-service"]

