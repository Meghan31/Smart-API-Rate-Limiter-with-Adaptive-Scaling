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

## 🐳 Docker Deployment

### Building the Docker Image

The project includes an optimized multi-stage Dockerfile that produces a production-ready image < 250MB.

```bash
# Build the Docker image
docker build -t smart-rate-limiter:latest .

# Verify image size
docker images smart-rate-limiter
```

### Running with Docker Compose

The easiest way to run the complete stack (application + Redis):

```bash
# Start all services
docker-compose up -d

# Check service status
docker-compose ps

# View logs
docker-compose logs -f app

# Test the application
curl http://localhost:8080/actuator/health

# Stop all services
docker-compose down
```

### Docker Compose Services

- **app**: Smart Rate Limiter application (port 8080)
- **redis**: Redis cache (port 6379)
- **redis-commander**: Redis web UI (port 8081)

### Environment Variables

Configure the application via environment variables in `docker-compose.yml`:

- `SPRING_DATA_REDIS_HOST`: Redis host (default: redis)
- `SPRING_DATA_REDIS_PORT`: Redis port (default: 6379)
- `RATE_LIMITER_TOKEN_BUCKET_CAPACITY`: Token bucket capacity (default: 100)
- `RATE_LIMITER_TOKEN_BUCKET_REFILL_RATE`: Refill rate per second (default: 10.0)

## ☸️ Kubernetes Deployment

### Prerequisites

- Kubernetes cluster (minikube, kind, or cloud provider)
- kubectl configured
- Docker for building images

### Quick Deploy to Kubernetes

```bash
# 1. Build Docker image
docker build -t smart-rate-limiter:latest .

# 2. Deploy everything to Kubernetes
cd k8s
./deploy.sh

# 3. Test the deployment
./test.sh
```

### Manual Deployment Steps

```bash
# Create namespace
kubectl apply -f k8s/namespace.yaml

# Deploy Redis
kubectl apply -f k8s/redis-deployment.yaml
kubectl apply -f k8s/redis-service.yaml

# Deploy application
kubectl apply -f k8s/app-configmap.yaml
kubectl apply -f k8s/app-deployment.yaml
kubectl apply -f k8s/app-service.yaml

# Deploy autoscaler
kubectl apply -f k8s/hpa.yaml

# Check deployment status
kubectl get all -n rate-limiter
```

### Accessing the Application

**Port-forward for local testing:**

```bash
kubectl port-forward svc/rate-limiter-service 8080:80 -n rate-limiter
curl http://localhost:8080/actuator/health
```

**LoadBalancer (cloud environments):**

```bash
kubectl get svc rate-limiter-service -n rate-limiter
# Use the EXTERNAL-IP to access the application
```

### Kubernetes Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    rate-limiter Namespace               │
│                                                           │
│  ┌──────────────────────────────────────────────────┐   │
│  │  Horizontal Pod Autoscaler (2-10 replicas)       │   │
│  └─────────────────┬────────────────────────────────┘   │
│                    │                                     │
│  ┌─────────────────▼────────────────────────────────┐   │
│  │  Deployment: rate-limiter-app (3 replicas)       │   │
│  │  - Resources: CPU 250m-500m, Memory 512Mi-1Gi   │   │
│  │  - Liveness/Readiness Probes                     │   │
│  │  - Graceful Shutdown (30s)                       │   │
│  └─────────────────┬────────────────────────────────┘   │
│                    │                                     │
│  ┌─────────────────▼────────────────────────────────┐   │
│  │  Service: rate-limiter-service (LoadBalancer)    │   │
│  │  Port: 80 → 8080                                 │   │
│  └──────────────────────────────────────────────────┘   │
│                                                           │
│  ┌──────────────────────────────────────────────────┐   │
│  │  StatefulSet: redis (1 replica)                  │   │
│  │  - PersistentVolumeClaim: 1Gi                    │   │
│  │  - Resources: CPU 200m, Memory 256Mi             │   │
│  └─────────────────┬────────────────────────────────┘   │
│                    │                                     │
│  ┌─────────────────▼────────────────────────────────┐   │
│  │  Service: redis (ClusterIP/Headless)             │   │
│  │  Port: 6379                                       │   │
│  └──────────────────────────────────────────────────┘   │
│                                                           │
│  ┌──────────────────────────────────────────────────┐   │
│  │  ConfigMap: rate-limiter-config                  │   │
│  │  - Redis connection settings                     │   │
│  │  - Rate limiter configuration                    │   │
│  │  - JVM tuning parameters                         │   │
│  └──────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### Scaling

**Manual scaling:**

```bash
kubectl scale deployment rate-limiter-app --replicas=5 -n rate-limiter
```

**Auto-scaling (HPA):**

The Horizontal Pod Autoscaler automatically scales based on:
- CPU utilization (target: 70%)
- Memory utilization (target: 80%)
- Min replicas: 2
- Max replicas: 10

```bash
# Check HPA status
kubectl get hpa -n rate-limiter

# View HPA details
kubectl describe hpa rate-limiter-hpa -n rate-limiter
```

**Note:** HPA requires metrics-server to be installed:

```bash
# For minikube
minikube addons enable metrics-server

# For other clusters
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

### Monitoring

**View pod status:**

```bash
kubectl get pods -n rate-limiter
kubectl describe pod <pod-name> -n rate-limiter
```

**View logs:**

```bash
# All pods
kubectl logs -l app=smart-rate-limiter -n rate-limiter --tail=100 -f

# Specific pod
kubectl logs <pod-name> -n rate-limiter -f
```

**Health checks:**

```bash
# Liveness probe
kubectl exec -n rate-limiter <pod-name> -- curl -s http://localhost:8080/actuator/health/liveness

# Readiness probe
kubectl exec -n rate-limiter <pod-name> -- curl -s http://localhost:8080/actuator/health/readiness
```

**Resource usage:**

```bash
kubectl top pods -n rate-limiter
kubectl top nodes
```

### Cleanup

```bash
# Delete all resources (keeps namespace)
cd k8s
./cleanup.sh --keep-namespace

# Delete everything including namespace
./cleanup.sh

# Or manually
kubectl delete namespace rate-limiter
```

### Kubernetes Configuration Files

Located in the `k8s/` directory:

- `namespace.yaml`: Creates the rate-limiter namespace
- `redis-deployment.yaml`: StatefulSet for Redis with persistent storage
- `redis-service.yaml`: ClusterIP service for Redis
- `app-configmap.yaml`: Application configuration
- `app-deployment.yaml`: Application deployment with 3 replicas
- `app-service.yaml`: LoadBalancer service for external access
- `hpa.yaml`: Horizontal Pod Autoscaler configuration
- `deploy.sh`: Automated deployment script
- `test.sh`: Testing script for deployed application
- `cleanup.sh`: Cleanup script to remove all resources

### Troubleshooting

**Pods not starting:**

```bash
kubectl get pods -n rate-limiter
kubectl describe pod <pod-name> -n rate-limiter
kubectl logs <pod-name> -n rate-limiter
```

**Redis connection issues:**

```bash
# Test Redis connectivity
kubectl exec -n rate-limiter <app-pod-name> -- nc -zv redis-0.redis.rate-limiter.svc.cluster.local 6379

# Check Redis pod
kubectl logs -n rate-limiter <redis-pod-name>
```

**Image pull errors:**

For local images in minikube:

```bash
# Load image into minikube
minikube image load smart-rate-limiter:latest

# Or build inside minikube
eval $(minikube docker-env)
docker build -t smart-rate-limiter:latest .
```

**Service not accessible:**

```bash
# Check service
kubectl get svc -n rate-limiter

# Check endpoints
kubectl get endpoints -n rate-limiter

# Use port-forward as alternative
kubectl port-forward svc/rate-limiter-service 8080:80 -n rate-limiter
```

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
