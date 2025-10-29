# syntax=docker/dockerfile:1

FROM gradle:8.7-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew --no-daemon clean shadowJar

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV PORT=4000 \
    UPSTREAMS_CONFIG=/app/config/upstreams.yaml
COPY --from=build /workspace/build/libs/graphql-gateway.jar /app/graphql-gateway.jar
COPY config /app/config
EXPOSE 4000
ENTRYPOINT ["java", "-jar", "/app/graphql-gateway.jar"]
