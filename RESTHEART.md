# RESTHeart — HTTP API for MongoDB

## Overview

[RESTHeart](https://restheart.org/) exposes MongoDB as a REST, GraphQL, and
WebSocket API with zero backend code. It runs alongside the Spring Boot admin
app in Docker and connects to the same mongod instance.

## Port Map

| Port  | Service             |
|-------|---------------------|
| 9811  | Spring Boot (admin) |
| 9812  | mongod              |
| 9813  | mongo-express       |
| 9814  | RESTHeart (data API)|

## Setup

### 1. Copy docker-compose to the host

```bash
scp -r restheart/ user@<host-ip>:~/mongodb-server/
```

### 2. Start RESTHeart

```bash
ssh user@<host-ip>
cd ~/mongodb-server/restheart
docker compose up -d
docker compose logs -f   # watch for "Greetings from RESTHeart!"
```

RESTHeart reads `MONGODB_ROOT_USERNAME` and `MONGODB_ROOT_PASSWORD` from
`../.env` (same file used by `compose.yaml`).

### 3. Test locally

```bash
curl http://localhost:9814/ping
# → {"message":"Greetings from RESTHeart!", ...}
```

### 4. Add Cloudflare tunnel route

In the cloudflared `config.yml`:

```yaml
ingress:
  - hostname: api.<your-domain>
    service: http://localhost:9814
  # ... existing routes ...
```

Restart cloudflared:

```bash
sudo systemctl restart cloudflared
```

In the Cloudflare DNS dashboard, add a CNAME:

```
api.<your-domain> → <tunnel-id>.cfargotunnel.com
```

### 5. Verify from outside

```bash
curl https://api.<your-domain>/ping
# → {"message":"Greetings from RESTHeart!", ...}
```

## Client Usage

Set these env vars in your client app:

```
DATABASE_API_URL=https://api.<your-domain>
DB_USER=<db-username>
DB_PASS=<db-password>
```

Authentication is HTTP Basic — the same credentials that were provisioned for
the target database. RESTHeart's `MongoDBRealmAuthenticator` delegates
authentication to MongoDB itself.

See the [RESTHeart docs](https://restheart.org/docs/) for the full API
reference, aggregation pipelines, GraphQL, WebSocket subscriptions, and
plugin development.
