# GraphQL Gateway

A high-performance GraphQL gateway that aggregates multiple upstream GraphQL services into a unified API. Built with **Kotlin**, **Ktor (Netty engine)**, and **graphql-java** on JVM 21+.

## Features

- **Schema Aggregation**: Automatically introspects and merges multiple upstream GraphQL schemas
- **Priority-based Conflict Resolution**: Resolves field conflicts using configurable priority (lower number = higher priority)
- **Intelligent Routing**: Routes queries to appropriate upstream services based on field ownership
- **Local Introspection**: Handles GraphQL introspection queries locally without forwarding to upstreams
- **Health Monitoring**: Provides health and readiness probes for container orchestration
- **Docker Support**: Containerized deployment with multi-stage builds

## Quick Start

### Prerequisites
- Java Development Kit (JDK) 21 or later
- Docker (optional, for containerized deployment)

### Development Mode

1. **Install dependencies and run tests**:
```bash
./gradlew test
```

2. **Start the development server**:
```bash
./gradlew run
```

The server will start on port `4000` by default. You can override this with the `PORT` environment variable.

3. **Verify the service is running**:
```bash
# Health check
curl -s http://localhost:4000/healthz

# Readiness check (requires upstream services to be available)
curl -s http://localhost:4000/readyz

# Get merged schema
curl -s http://localhost:4000/schema
```

### Configuration

The gateway reads upstream service configuration from `config/upstreams.yaml`:

```yaml
upstreams:
  - name: Commons
    url: http://172.29.5.219:8080/commonsGraphQL
    priority: 0  # Lower number = higher priority
  - name: Schools
    url: http://172.29.5.219:8081/schoolAffairsGraphQL
    priority: 1
  - name: Students
    url: http://172.29.5.219:8082/studentsGraphQL
    priority: 2
```

**Priority Rules**: Lower numbers have higher priority. When fields conflict between services, the service with the lower priority number wins.

### GraphQL Usage

**Query the gateway**:
```bash
curl -X POST http://localhost:4000/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ students { id name } }"}'
```

**Introspection** (handled locally):
```bash
curl -X POST http://localhost:4000/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ __schema { queryType { name } } }"}'
```

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/graphql` | POST | Main GraphQL endpoint for queries and mutations |
| `/schema` | GET | Returns the merged GraphQL schema as SDL |
| `/healthz` | GET | Basic health check (always returns 200) |
| `/readyz` | GET | Readiness check (returns 503 if upstreams unavailable) |

## Architecture

### Schema Merging Strategy

1. **Root Fields**: Query and Mutation fields are merged by priority
2. **Type Definitions**: Object and Input types are merged, with field conflicts resolved by priority
3. **Conflict Resolution**: When the same field exists in multiple services, the service with the lower priority number wins
4. **Reachability**: Only types reachable from root fields are included in the final schema

### Request Routing

- **Single Service Rule**: Each GraphQL operation must only query fields from one upstream service
- **Cross-Service Queries**: Requests spanning multiple services return a 400 error
- **Header Forwarding**: Authorization and other headers are forwarded to upstream services

## Project Structure

```
├── src/main/kotlin/com/gateway/
│   ├── Application.kt              # Main Ktor application
│   ├── config/                     # Configuration loading
│   ├── graphql/                    # GraphQL execution and introspection
│   ├── health/                     # Health and readiness checks
│   ├── introspection/              # Upstream schema introspection
│   ├── routing/                    # Request routing and forwarding
│   └── schema/                     # Schema merging and composition
├── config/
│   └── upstreams.yaml              # Upstream service configuration
├── build.gradle.kts                # Gradle build configuration
├── Dockerfile                      # Multi-stage container build
├── docker-compose.yml              # Container orchestration example
└── todos.md                        # Development task tracking
```

## Container Deployment

### Build and Run with Docker

```bash
# Build the container
docker build -t graphql-gateway:latest .

# Run with custom configuration
docker run -p 4000:4000 \
  -v $(pwd)/config/upstreams.yaml:/app/config/upstreams.yaml:ro \
  graphql-gateway:latest
```

### Using Docker Compose

```bash
docker-compose up --build
```

## Development

### Running Tests

```bash
./gradlew test
```

### Building Fat JAR

```bash
./gradlew shadowJar
# Output: build/libs/graphql-gateway.jar
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `4000` | Server port |
| `UPSTREAMS_CONFIG` | `config/upstreams.yaml` | Path to upstream configuration |

## Limitations

- **Single Service Queries**: Each GraphQL operation must target only one upstream service
- **No Fragment Support**: Fragment spreads and inline fragments are not supported in routing
- **No Subscriptions**: Subscription operations are not currently supported
- **Limited Type Support**: Only Object and Input types are merged; Interfaces, Unions, and Enums are not yet supported

## Technology Stack

- **Runtime**: JVM 21+
- **Language**: Kotlin
- **HTTP Server**: Ktor with Netty engine
- **GraphQL**: graphql-java
- **Configuration**: YAML with Jackson
- **Containerization**: Multi-stage Docker builds
- **Build Tool**: Gradle with Shadow plugin

## Contributing

This project follows a structured development process documented in `agents.md`. See `todos.md` for current development tasks and status.
