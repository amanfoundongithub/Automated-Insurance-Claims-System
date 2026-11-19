# Redis local setup

The Spring services use Redis for event deduplication and idempotency. This guide starts a local Redis instance with Docker; it does not require adding a Redis dependency to the project.

## Requirements

- Docker Desktop or Docker Engine running.
- Host port `6379` available.

## Start Redis

Run from a terminal:

```bash
docker run -d \
  --name claims-redis \
  --restart unless-stopped \
  -p 6379:6379 \
  -v claims-redis-data:/data \
  redis:7-alpine \
  redis-server --appendonly yes
```

The named volume keeps Redis data when the container is stopped or replaced.

Check that Redis is ready:

```bash
docker exec claims-redis redis-cli ping
```

Expected output:

```text
PONG
```

## Connect the application

The local Spring configuration expects Redis on the host at:

| Setting | Local value |
| --- | --- |
| `REDIS_HOST` | `localhost` |
| `REDIS_PORT` | `6379` |
| `REDIS_TIMEOUT` | `2s` where configured |

These settings are in each service's `src/main/resources/application-local.yaml`. If an application is itself running in Docker on the same Docker network, use the Redis container/service name (for example, `claims-redis`) for `REDIS_HOST` instead of `localhost`.

## Stop and restart

```bash
docker stop claims-redis
docker start claims-redis
```

To remove only the container while retaining its data volume:

```bash
docker rm -f claims-redis
```

## Troubleshooting

- **Connection refused:** confirm the container is running with `docker ps` and that port `6379` is available.
- **Wrong host from a container:** `localhost` inside an application container points to that container, not Redis. Use the Redis service/container name on the shared Docker network.
- **Check Redis logs:** `docker logs claims-redis`.

This setup has no Redis password and is intended for local development only. Do not expose port `6379` to an untrusted network.
