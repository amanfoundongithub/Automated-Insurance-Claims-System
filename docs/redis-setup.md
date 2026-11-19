# Redis local setup

The application services use Redis to support request idempotency and event deduplication. This guide starts a local Redis instance with Docker; it does not add a Redis dependency to the project.

## Requirements

- Docker Desktop or Docker Engine running.
- Host port `6379` available.

## Start Redis

Run from a terminal:

```bash
docker run -d \
  --name claims-redis \
  --restart unless-stopped \
  -p 127.0.0.1:6379:6379 \
  -v claims-redis-data:/data \
  redis:7-alpine \
  redis-server --appendonly yes
```

The named volume and append-only file preserve Redis data when the container is stopped or replaced. Check that Redis is ready:

```bash
docker exec claims-redis redis-cli ping
```

Expected output:

```text
PONG
```

## Connect the services

For applications running directly on the host, use:

| Setting | Local value |
| --- | --- |
| `REDIS_HOST` | `localhost` |
| `REDIS_PORT` | `6379` |

These are the defaults in the Python services and are set in the Java services' local configuration. Where `REDIS_TIMEOUT` is configured, Java expects a duration such as `2s`; Python expects a number of seconds such as `2.0`.

If an application runs in a container, connect it and Redis to the same user-defined Docker network and set `REDIS_HOST=claims-redis`. `localhost` inside an application container points to that container, not to Redis.

## Stop, restart, or remove

```bash
docker stop claims-redis
docker start claims-redis
```

Remove the container while retaining its data volume:

```bash
docker rm -f claims-redis
```

To delete the Redis data as well, remove the volume:

```bash
docker volume rm claims-redis-data
```

## Troubleshooting

- **Connection refused:** confirm the container is running with `docker ps`, Redis is ready, and port `6379` is available.
- **Wrong host from a container:** use `claims-redis` on a shared user-defined Docker network instead of `localhost`.
- **Inspect Redis logs:** `docker logs claims-redis`.

This local setup has no Redis password and publishes port `6379` only on loopback. Do not expose it to an untrusted network; use authentication and appropriate network controls outside local development.
