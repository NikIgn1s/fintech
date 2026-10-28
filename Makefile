COMPOSE = docker compose --env-file .env -f infra/docker-compose.yml

.PHONY: up down logs ps

up:
	$(COMPOSE) up -d --build

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f finflow-core finflow-ml

ps:
	$(COMPOSE) ps
