# RESTHeart — HTTP API for MongoDB

## Overview

[RESTHeart](https://restheart.org/) exposes MongoDB as a REST, GraphQL, and
WebSocket API with zero backend code. It is bundled in the Docker Compose stack
(`compose.yaml`) and starts automatically with `docker compose up -d`.

## Port Map

| Port  | Service             |
|-------|---------------------|
| 9811  | Spring Boot (admin) |
| 9812  | mongod              |
| 9813  | mongo-express       |
| 9814  | RESTHeart (data API)|

## Password Hashing

RESTHeart's `MongoDBRealmAuthenticator` expects passwords as **bcrypt hashes**
(the same format used by Spring Security's `BCryptPasswordEncoder`). The admin
UI at `/restheart/users` manages this automatically — passwords are bcrypt-hashed
before storage.

To hash a password manually (for scripted provisioning):

```bash
htpasswd -bnBC 12 "" 'your_plaintext_password' | tr -d ':\n' | sed 's/$2y/$2a/'
```

Or use any bcrypt tool. Store the full hash string (including the `$2a$` prefix)
as the `password` field in `restheart.users`.

## Getting Started

RESTHeart starts as part of the main Docker Compose stack. No separate setup
is needed.

### 1. Start the stack

```bash
docker compose up -d
```

This starts MongoDB, mongo-express, and RESTHeart together.

### 2. Verify RESTHeart is running

```bash
curl http://localhost:9814/ping
# → {"message":"Greetings from RESTHeart!", ...}
```

### 3. Manage users and ACL via the admin UI

Open the Spring Boot admin app (`http://localhost:9811`) and navigate to:

- **API Users** (`/restheart/users`) — create, reset passwords, delete RESTHeart
  users. Passwords are bcrypt-hashed before storage.
- **ACL Rules** (`/restheart/acl`) — define which URL patterns each role can
  access through the RESTHeart API.

### 4. Add Cloudflare tunnel route (optional)

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
RESTHEART_URL=https://api.<your-domain>
DB_USER=<restheart-username>
DB_PASS=<restheart-password>
MONGODB_DB=<target-database>
```

Authentication is HTTP Basic — the credentials are managed in the admin UI
at `/restheart/users`.

## Configuration

RESTHeart is configured via the `RHO` environment variable in `compose.yaml`.
Key settings:

- **MongoDB connection**: `${MONGODB_ROOT_USERNAME}:${MONGODB_ROOT_PASSWORD}@127.0.0.1:9812`
- **Authentication**: `MongoDBRealmAuthenticator` — reads users from `restheart.users`
- **Authorization**: `MongoAuthorizer` — reads ACL rules from `restheart.acl`
- **Port**: 9814 (host networking)

See the [RESTHeart docs](https://restheart.org/docs/) for the full API
reference, aggregation pipelines, GraphQL, WebSocket subscriptions, and
plugin development.
