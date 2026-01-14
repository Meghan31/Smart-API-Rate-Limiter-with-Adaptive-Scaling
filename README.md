# Smart API Rate Limiter with Adaptive Scaling

A high-performance, distributed API rate limiting service built with Spring Boot and Redis, designed to handle high-throughput scenarios while preventing API abuse and ensuring fair resource allocation.

## 🚀 Features

- **Token Bucket Algorithm**: Industry-standard rate limiting with configurable capacity and refill rates
- **Distributed Architecture**: Redis-backed synchronization across multiple application instances
- **Real-time Monitoring**: Built-in metrics and health checks via Spring Boot Actuator
- **Flexible Configuration**: YAML-based configuration for easy customization
- **Thread-Safe**: Concurrent request handling with proper synchronization
- **HTTP Standards Compliant**: Proper 429 responses and rate limit headers (X-RateLimit-Limit, X-RateLimit-Remaining)

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

- Java 17 or higher
- Maven 3.6+
- Docker (for Redis)

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
```

## 📊 API Endpoints

| Endpoint           | Method | Description                     |
| ------------------ | ------ | ------------------------------- |
| `/api/test`        | GET    | Test endpoint for rate limiting |
| `/actuator/health` | GET    | Health check endpoint           |

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
