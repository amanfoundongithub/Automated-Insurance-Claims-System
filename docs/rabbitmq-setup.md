# RabbitMQ local setup

RabbitMQ transports claim and settlement events between the six application services. The services declare the exchanges, queues, and bindings they use when they start; you do not need to create the project topology manually.

## Requirements

- Docker Desktop or Docker Engine running.
- Host ports `5672` (AMQP) and `15672` (management UI) available.

## Start RabbitMQ

Run from a terminal:

```bash
docker run -d \
  --name claims-rabbitmq \
  --restart unless-stopped \
  -p 127.0.0.1:5672:5672 \
  -p 127.0.0.1:15672:15672 \
  -v claims-rabbitmq-data:/var/lib/rabbitmq \
  rabbitmq:3-management
```

The named volume preserves broker data when the container is stopped or replaced. Wait for RabbitMQ to become ready:

```bash
docker exec claims-rabbitmq rabbitmq-diagnostics -q ping
```

Open the management UI at [http://localhost:15672](http://localhost:15672). The default local login is `guest` / `guest`.

## Connect the services

For applications running directly on the host, the local Spring configurations and Python service defaults use:

| Setting | Local value |
| --- | --- |
| `RABBITMQ_HOST` | `localhost` |
| `RABBITMQ_PORT` | `5672` |
| `RABBITMQ_USERNAME` | `guest` |
| `RABBITMQ_PASSWORD` | `guest` |

The project uses the topic exchanges `claims.events.topic` and `claims.events.dlx`. Services declare their queues and bindings during startup. Use the management UI's **Connections**, **Exchanges**, and **Queues** tabs to inspect broker activity.

RabbitMQ restricts the built-in `guest` account to connections from the local machine. If applications run in containers, create a dedicated development account instead. Run these commands once, replacing the example password with a local-only value:

```bash
docker exec claims-rabbitmq rabbitmqctl add_user claims_dev replace-with-a-local-password
docker exec claims-rabbitmq rabbitmqctl set_permissions -p / claims_dev ".*" ".*" ".*"
```

Set `RABBITMQ_USERNAME=claims_dev` and `RABBITMQ_PASSWORD` to that password for the application. Do not commit credentials. For containerized applications, connect the application containers and RabbitMQ to the same user-defined Docker network, then set `RABBITMQ_HOST` to `claims-rabbitmq`.

## Stop, restart, or remove

```bash
docker stop claims-rabbitmq
docker start claims-rabbitmq
```

Remove the container while retaining its data volume:

```bash
docker rm -f claims-rabbitmq
```

To delete the broker data as well, remove the volume:

```bash
docker volume rm claims-rabbitmq-data
```

## Troubleshooting

- **Connection refused:** confirm the broker is ready, port `5672` is available, and `RABBITMQ_HOST` is correct.
- **Authentication refused:** verify the configured username and password. Use the `guest` account only for host-local development.
- **No queues or bindings are visible:** start the application services and check their logs; they declare their topology at startup.
- **Inspect broker logs:** `docker logs claims-rabbitmq`.

The published ports are bound to loopback for local development. Do not expose AMQP or the management UI to an untrusted network; use dedicated credentials and appropriate network controls outside local development.
