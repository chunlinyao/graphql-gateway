# GraphQL Gateway

This project implements a custom GraphQL gateway using **Kotlin**, **Ktor (Netty engine)**, and **graphql-java**. The gateway will load upstream schemas, merge them using a priority-based strategy, and expose a single public `/graphql` endpoint alongside operational probes.

## Getting Started

### Prerequisites
- Java Development Kit (JDK) 21 or later
- (Optional) Docker for container builds in later milestones

### Install Dependencies
All build tooling is managed through the included Gradle Wrapper.

```bash
./gradlew --version
```

### Run the Development Server

```bash
./gradlew run
```

The server listens on port `4000` by default. Override with the `PORT` environment variable if needed.

### Verify the Health Endpoint

```bash
curl -s http://localhost:4000/healthz
```

Expected response:

```json
{"status":"ok"}
```

## Project Structure

```
├── build.gradle.kts        # Gradle build configuration (Kotlin DSL)
├── settings.gradle.kts     # Gradle settings
├── gradlew / gradlew.bat   # Gradle Wrapper scripts
├── src/main/kotlin         # Kotlin sources (Ktor server entry point lives here)
└── todos.md                # Task tracking and hand-off details
```

## Stack Requirements

- Runtime: JVM 21+
- Language: Kotlin
- HTTP server: Ktor (Netty engine)
- GraphQL tooling: graphql-java

These requirements are non-negotiable and supersede any earlier Node.js/Express assumptions.
