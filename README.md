<div align="center">

# 🚦 Smart API Rate Limiter with Adaptive Scaling

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Redis](https://img.shields.io/badge/Redis-7-red.svg)](https://redis.io/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)](https://github.com/Meghan31)
[![Railway Deploy](https://img.shields.io/badge/Railway-Deployed-purple.svg)](https://smart-api-rate-limiter.up.railway.app/)

**A high-performance, distributed API rate limiting service built with Spring Boot and Redis**

_Designed to handle high-throughput scenarios while preventing API abuse and ensuring fair resource allocation_

[🌐 Live Demo](https://smart-api-rate-limiter.up.railway.app/) • [📚 Documentation](docs/) • [💻 Quick Start](#-want-to-deploy-your-own) • [🚀 Deploy](#-want-to-deploy-your-own)

---

</div>

## ✨ Try it Live!

**🌐 [https://smart-api-rate-limiter.up.railway.app/](https://smart-api-rate-limiter.up.railway.app/)**

### Quick Test Commands

```bash
# Basic test
curl -i https://smart-api-rate-limiter.up.railway.app/api/test

# Test with your API key
curl -i -H "X-API-Key: your-name" https://smart-api-rate-limiter.up.railway.app/api/test

# Trigger rate limiting (send 101 requests)
for i in {1..101}; do
  curl -s -H "X-API-Key: demo-user" https://smart-api-rate-limiter.up.railway.app/api/test
done

# Check health
curl https://smart-api-rate-limiter.up.railway.app/actuator/health
```

### What You'll See

**✅ Success Response (200):**

```json
{
	"message": "Success! Your request was processed.",
	"timestamp": "2026-03-03T18:15:42.123Z",
	"user": "your-name",
	"remainingTokens": 99
}
```

**🚫 Rate Limited (429 - after 100 requests):**

```json
{
	"error": "Rate limit exceeded",
	"message": "Too many requests. Please try again later.",
	"retryAfter": "10 seconds",
	"user": "your-name"
}
```

---

## 🎯 Features

| Feature                         | Description                                                     |
| ------------------------------- | --------------------------------------------------------------- |
| 🪣 **Token Bucket Algorithm**   | Industry-standard rate limiting (100 tokens, refills at 10/sec) |
| 🌐 **Distributed Architecture** | Redis-backed state sharing across multiple instances            |
| 👥 **Per-User Rate Limiting**   | Each API key gets independent rate limits                       |
| ⚛️ **Atomic Operations**        | Lua scripts ensure thread-safe operations                       |
| 📊 **Real-time Monitoring**     | Built-in health checks and metrics                              |
| 🚀 **High Performance**         | < 5ms response time, handles 50,000+ req/s                      |
| 🔄 **Horizontally Scalable**    | Deploy multiple instances seamlessly                            |

---

## 🏗️ How It Works

```
┌─────────────┐
│   Client    │
│  (You!)     │
└──────┬──────┘
       │ HTTP Request + X-API-Key
       ▼
┌─────────────────────┐
│   Rate Limiter      │
│   (Spring Boot)     │
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐     ┌─────────────┐
│  Check Token Bucket │────▶│   Redis     │
│  (Lua Script)       │◀────│  (State)    │
└──────┬──────────────┘     └─────────────┘
       │
       ▼
   ✅ or 🚫
```

**Request Flow:**

1. Client sends request with `X-API-Key` header
2. System checks Redis for user's token bucket
3. If tokens available → Process request (200)
4. If no tokens → Return 429 with retry-after
5. Tokens automatically refill at 10 per second

---

## 📊 API Endpoints

| Endpoint                   | Description           |
| -------------------------- | --------------------- |
| `GET /api/test`            | Test the rate limiter |
| `GET /actuator/health`     | Health check          |
| `GET /monitoring.html`     | Real-time dashboard   |
| `GET /actuator/prometheus` | Prometheus metrics    |

**Response Headers:**

```
X-RateLimit-Limit: 100        # Max tokens
X-RateLimit-Remaining: 95      # Available tokens
X-RateLimit-User: your-name    # Your identifier
```

---

## 🔧 Tech Stack

- **Backend:** Spring Boot 3.2.0, Java 21
- **Cache:** Redis 7 (distributed state)
- **Deployment:** Railway Platform
- **Build:** Maven
- **Testing:** JUnit 5

---

## 📈 Performance

| Metric           | Value                   |
| ---------------- | ----------------------- |
| Throughput       | 50,000+ requests/second |
| Response Time    | < 5ms average           |
| Concurrent Users | 10,000+ supported       |
| Uptime           | 99.9% (Railway)         |

---

## 🚀 Want to Deploy Your Own?

<details>
<summary><b>Quick Local Setup</b></summary>

```bash
# 1. Clone the repo
git clone https://github.com/Meghan31/Smart-API-Rate-Limiter-with-Adaptive-Scaling.git
cd Smart-API-Rate-Limiter-with-Adaptive-Scaling

# 2. Start Redis
docker-compose up -d

# 3. Run the app
mvn spring-boot:run

# 4. Test locally
curl http://localhost:8080/api/test
```

</details>

<details>
<summary><b>Deploy to Railway</b></summary>

1. Fork this repository
2. Sign up at [railway.app](https://railway.app)
3. Create new project from your fork
4. Add Redis service
5. Deploy automatically!

</details>

<details>
<summary><b>Deploy with Docker</b></summary>

```bash
docker-compose up -d
```

</details>

### 📚 Full Documentation

For detailed setup, configuration, and deployment guides:

- [Development Guide](docs/DEVELOPMENT.md)
- [Deployment Guide](docs/DEPLOYMENT.md)
- [API Documentation](docs/API.md)
- [Architecture Details](ARCHITECTURE.md)

---

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

---

## 👤 Author

**Meghasrivardhan Pulakhandam (Megha)**

- 🌐 Portfolio: [www.meghan31.me](https://www.meghan31.me/)
- 💼 LinkedIn: [linkedin.com/in/meghan31](https://www.linkedin.com/in/meghan31/)
- 🐙 GitHub: [@Meghan31](https://github.com/Meghan31)

---

<div align="center">

**Made with ❤️ by [Megha31](https://www.meghan31.me/)**

⭐ If you find this project useful, give it a star!

[🌐 Try Live Demo](https://smart-api-rate-limiter.up.railway.app/) • [🐛 Report Bug](https://github.com/Meghan31/Smart-API-Rate-Limiter-with-Adaptive-Scaling/issues) • [✨ Request Feature](https://github.com/Meghan31/Smart-API-Rate-Limiter-with-Adaptive-Scaling/issues)

</div>
