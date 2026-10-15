# Food Delivery Backend

## Service Ports

| Service | Port | Health check |
|---|---:|---|
| API Gateway | `8080` | `GET http://localhost:8080/gateway/health` |
| Auth Service | `8081` | `GET http://localhost:8081/auth/health` |
| Restaurant Service | `8082` | `GET http://localhost:8082/restaurant/health` |
| Order Service | `8083` | `GET http://localhost:8083/order/health` |
| Delivery Service | `8084` | `GET http://localhost:8084/delivery/health` |
| Chat Service | `8085` | `GET http://localhost:8085/chat/health` |