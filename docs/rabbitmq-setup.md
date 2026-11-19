# RabbitMQ local setup

RabbitMQ carries claim-processing and settlement events between services. The Spring services declare their exchanges, queues, and bindings when they start; you normally do not need to create the project topology manually.

## Requirements

- Docker Desktop or Docker Engine running.
- Host ports `5672` (AMQP) and `15672` (management UI) available.

## Start RabbitMQ

Run from a terminal:

```bash
docker run -d \
  --name claims-rabbitmq \
  --restart unless-stopped \
  -p 5672:5672 \
  -p 15672:15672 \
  rabbitmq:3-management
```

Wait until the broker responds:

```bash
docker exec claims-rabbitmq rabbitmq-diagnostics -q ping
```

The management UI is available at [http://localhost:15672](http://localhost:15672). For this local container, its default login is `guest` / `guest`.

## Connect the application

The local Spring configurations expect the broker on the host at:

| Setting | Local value |
| --- | --- |
| `RABBITMQ_HOST` | `localhost` |
| `RABBITMQ_PORT` | `5672` |
| `RABBITMQ_USERNAME` | `guest` |
| `RABBITMQ_PASSWORD` | `guest` |

Use the management UI's **Connections**, **Exchanges**, and **Queues** tabs to check activity after starting the services. The project uses the topic exchanges `claims.events.topic` and `claims.events.dlx`. For example, settlement uses `settlement.requested`, `settlement.completed`, and `settlement.failed` routing keys.

RabbitMQ restricts the built-in `guest` account to local connections. If a local application is denied access, or if applications are running in Docker, create a dedicated development account:

```bash
docker exec claims-rabbitmq rabbitmqctl add_user claims_dev replace-with-a-local-password
docker exec claims-rabbitmq rabbitmqctl set_permissions -p / claims_dev ".*" ".*" ".*"
```

Set `RABBITMQ_USERNAME=claims_dev` and `RABBITMQ_PASSWORD` to the same password in the application environment. When applications run in Docker, connect them to the same Docker network and set `RABBITMQ_HOST` to the RabbitMQ service/container name (for example, `claims-rabbitmq`). Do not enable remote access for `guest` or commit real passwords to the repository.

## Stop and restart

```bash
docker stop claims-rabbitmq
docker start claims-rabbitmq
```

To remove the container:

```bash
docker rm -f claims-rabbitmq
```

## Troubleshooting

- **Connection refused:** confirm the broker is ready, port `5672` is available, and `RABBITMQ_HOST` is correct.
- **Authentication refused:** verify the configured username and password. The `guest` account is only for local development connections.
- **No queues or bindings are visible:** start the relevant service and check its logs. Spring declares that service's topology during startup.
- **Check broker logs:** `docker logs claims-rabbitmq`.

Do not expose the AMQP or management ports to an untrusted network. Use dedicated credentials and appropriate network controls outside local development.
