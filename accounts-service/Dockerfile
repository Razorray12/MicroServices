FROM gradle:8.8-jdk17 AS builder
WORKDIR /app
COPY . .
RUN gradle --no-daemon clean build

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=builder /app/build/libs/app-all.jar /app/app.jar
EXPOSE 8082
ENV PORT=8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

