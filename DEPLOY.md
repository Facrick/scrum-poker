# Деплой Scrum Poker

## Требования
- Docker + Docker Compose (v2)
- Домен с A-записью, указывающей на сервер
- SSL-сертификат (инструкция ниже)

---

## 1. Получить SSL-сертификат (Let's Encrypt)

```bash
# Установить certbot (Ubuntu/Debian)
sudo apt install certbot

# Получить сертификат (порт 80 должен быть свободен)
sudo certbot certonly --standalone -d poker.yourcompany.com

# Скопировать сертификаты в папку проекта
mkdir -p nginx/certs
sudo cp /etc/letsencrypt/live/poker.yourcompany.com/fullchain.pem nginx/certs/
sudo cp /etc/letsencrypt/live/poker.yourcompany.com/privkey.pem   nginx/certs/
sudo chmod 644 nginx/certs/*.pem
```

---

## 2. Настроить окружение

```bash
cp .env.example .env
# Отредактировать .env — заменить домен:
# ALLOWED_ORIGIN=https://poker.yourcompany.com
```

---

## 3. Настроить nginx

В файле `nginx/nginx.conf` заменить `YOUR_DOMAIN` на реальный домен:
```
server_name poker.yourcompany.com;
```

---

## 4. Запустить

```bash
docker compose up -d --build
```

Проверить статус:
```bash
docker compose ps
docker compose logs -f app
```

---

## Обновление приложения

```bash
git pull
docker compose up -d --build app
```

---

## Продление сертификата (автоматически)

```bash
# Добавить в crontab (sudo crontab -e):
0 3 * * 1 certbot renew --quiet && \
  cp /etc/letsencrypt/live/poker.yourcompany.com/fullchain.pem /path/to/project/nginx/certs/ && \
  cp /etc/letsencrypt/live/poker.yourcompany.com/privkey.pem   /path/to/project/nginx/certs/ && \
  docker compose -f /path/to/project/docker-compose.yml exec nginx nginx -s reload
```

---

## Переменные окружения

| Переменная       | По умолчанию              | Описание                                |
|-----------------|---------------------------|-----------------------------------------|
| `ALLOWED_ORIGIN` | `http://localhost:8080`   | Домен приложения (CORS + WebSocket)     |
| `ROOM_TTL_HOURS` | `8`                       | Через сколько часов удалять комнаты     |
