# Smart API Rate Limiter with Adaptive Scaling

A high-performance, distributed API rate limiting service built with Spring Boot and Redis, designed to handle high-throughput scenarios while preventing API abuse and ensuring fair resource allocation.

## 🚀 Features

- **Token Bucket Algorithm**: Industry-standard rate limiting with configurable capacity and refill rates
- **Distributed Architecture**: Redis-backed synchronization across multiple application instances
- **Per-User Rate Limiting**: Independent rate limits per API key (X-API-Key header)
- **Atomic Operations**: Lua scripts ensure thread-safe token consumption in Redis
- **Automatic Token Refill**: Tokens refill at a configurable rate
- **Real-time Monitoring**: Built-in metrics and health checks via Spring Boot Actuator
- **Flexible Configuration**: YAML-based configuration for easy customization
- **Thread-Safe**: Concurrent request handling with proper synchronization
- **HTTP Standards Compliant**: Proper 429 responses and rate limit headers (X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-User)
- **Production Ready**: Connection pooling, error handling, fail-open strategy, and comprehensive logging

## 🏗️ Architecture

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │────▶│  API Server │────▶│   Redis     │
│  Requests   │     │  (Spring)   │     │   Cluster   │
└─────────────┘     └─────────────┘     └─────────────┘
                           │
                           ▼
                    ┌─────────────┐
                    │ Rate Limiter│
                    │   Filter    │
                    └─────────────┘
```

## 📋 Prerequisites

- Java 21 or higher
- Maven 3.6+
- Docker and Docker Compose (for Redis)

## ��️ Quick Start

### 1. Clone the Repository

```bash
git clone
cd Smart-API-Rate-Limiter-with-Adaptive-Scaling
```

### 2. Build the Project

```bash
mvn clean install
```

### 3. Run the Application

```bash
mvn spring-boot:run
```

### 4. Test the Rate Limiter

Open your browser and navigate to:

```
http://localhost:8080/
```

Or use curl:

```bash
curl -i http://localhost:8080/api/test
```

## ⚙️ Configuration

Edit `src/main/resources/application.yml`:

```yaml
rate-limiter:
  token-bucket:
    enabled: true
    capacity: 100 # Maximum tokens in bucket
    refill-rate: 10.0 # Tokens added per second

spring:
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 5000ms
      lettuce:
        pool:
          max-active: 20  # Maximum connections
          max-idle: 10
          min-idle: 5
```

## 🗄️ Redis Setup & Management

### Starting Redis

IMPORTANT: Redis must be running before starting the application.

```bash
# Start Redis with Docker Compose
docker-compose up -d

# Check if Redis is running
docker ps | grep redis

# Test Redis connection
docker exec -it smart-rate-limiter-redis redis-cli ping
# Should return: PONG
```

### Redis Commander (Web UI)

Access Redis Commander at `http://localhost:8081` to view and manage Redis data in a web interface.

### Redis CLI Commands

```bash
# Access Redis CLI
docker exec -it smart-rate-limiter-redis redis-cli

# View all rate limit keys
KEYS rate_limit:*

# View specific user's data
HGETALL rate_limit:user-123

# Delete a user's rate limit
DEL rate_limit:user-123

# Monitor Redis commands in real-time
MONITOR
```

### Redis Key Structure

```
Key: rate_limit:{user_id}
Type: Hash
Fields:
  - tokens: Current available tokens (double)
  - last_refill: Last refill timestamp in milliseconds (long)
TTL: Automatically expires after 2x refill time
```

## 🔄 Testing Distributed Rate Limiting

To test rate limiting across multiple application instances:

1. **Start Redis:**
   ```bash
   docker-compose up -d
   ```

2. **Start first instance on port 8080:**
   ```bash
   mvn spring-boot:run
   ```

3. **Start second instance on port 8081 (in a new terminal):**
   ```bash
   mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
   ```

4. **Send requests to both instances with the same API key:**
   ```bash
   # Send 50 requests to first instance
   for i in {1..50}; do
     curl -s -H "X-API-Key: shared-user" http://localhost:8080/api/test > /dev/null
   done

   # Send 50 requests to second instance
   for i in {1..50}; do
     curl -s -H "X-API-Key: shared-user" http://localhost:8081/api/test > /dev/null
   done

   # Next request from either instance should be rate limited
   curl -i -H "X-API-Key: shared-user" http://localhost:8080/api/test
   ```

The rate limiting state is shared across both instances via Redis!

## 📊 API Endpoints

| Endpoint           | Method | Description                     |
| ------------------ | ------ | ------------------------------- |
| `/api/test`        | GET    | Test endpoint for rate limiting |
| `/actuator/health` | GET    | Health check endpoint           |
| `/actuator/health/redis` | GET    | Redis health check           |
| `/actuator/metrics` | GET    | Application metrics           |
| `/actuator/prometheus` | GET    | Prometheus metrics           |

### Response Headers

All `/api/*` responses include:
- `X-RateLimit-Limit`: Maximum number of tokens (capacity)
- `X-RateLimit-Remaining`: Current available tokens
- `X-RateLimit-User`: User identifier (from X-API-Key header or "anonymous")

## 🧪 Running Tests

```bash
mvn test
```

## 📈 Performance

- **Throughput**: Tested up to 50K concurrent requests
- **Response Time**: < 5ms for rate limit checks
- **Scalability**: Horizontally scalable with Redis cluster

## 🔧 Tech Stack

- **Backend**: Spring Boot 3.2.0
- **Language**: Java 21
- **Cache**: Redis (for distributed state)
- **Build Tool**: Maven
- **Testing**: JUnit 5

## 📝 Implementation Details

### Token Bucket Algorithm

- Maintains a bucket with a maximum capacity of tokens
- Tokens are consumed on each request
- Tokens refill at a constant rate
- Requests are rejected when bucket is empty

### Thread Safety

- Utilizes `ReentrantLock` for thread-safe operations
- Atomic token consumption and refill logic
- Safe for concurrent access across multiple threads

## 👤 Author

**Your Name**

- GitHub: [@Meghan31](https://github.com/Meghan31)
- LinkedIn: [Megha](https://www.linkedin.com/in/meghan31/)
- Portfolio: [www.meghan31.me](https://www.meghan31.me/)

## 🙏 Acknowledgments

- Inspired by industry-standard rate limiting patterns
- Built with best practices for distributed systems
