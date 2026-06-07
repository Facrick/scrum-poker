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
| `LOG_PATH`       | `./logs`                  | Каталог файлов логов                    |
| `LOG_LEVEL_APP`  | `INFO`                    | Уровень логов пакета `com.scrumpoker`   |
| `SPRING_PROFILES_ACTIVE` | —                 | `json` — структурированные JSON-логи    |
| `GOOGLE_CLIENT_ID` | —                       | OAuth2 Google: Client ID                |
| `GOOGLE_CLIENT_SECRET` | —                   | OAuth2 Google: Client Secret            |
| `GITHUB_CLIENT_ID` | —                       | OAuth2 GitHub: Client ID                |
| `GITHUB_CLIENT_SECRET` | —                   | OAuth2 GitHub: Client Secret            |
| `JWT_SECRET`     | dev-ключ (небезопасно)    | Секрет подписи JWT ЛК, ≥ 32 байт — **обязателен в production** |
| `JWT_TTL_HOURS`  | `168`                     | Срок жизни токена ЛК в часах (7 дней)   |
| `DATABASE_URL`   | H2 in-memory              | Postgres — **обязателен в production**, иначе данные теряются при рестарте |

---

## База данных (важно для Railway!)

Без `DATABASE_URL` приложение работает на **H2 in-memory** — все сессии и
комнаты **стираются при каждом рестарте/редеплое**. В логах при старте будет
громкое предупреждение об этом.

### Railway

1. В проекте добавьте плагин **Postgres** (New → Database → PostgreSQL).
2. В сервисе приложения добавьте переменную-ссылку:
   ```
   DATABASE_URL=${{Postgres.DATABASE_URL}}
   ```
   Railway отдаёт её в формате `postgresql://user:pass@host:port/db` —
   приложение само сконвертирует её в JDBC (см.
   `RailwayDatabaseEnvironmentPostProcessor`), отдельный драйвер указывать не нужно.
3. Передеплойте. В логах должно появиться `Постоянное хранилище: jdbc:postgresql://…`.

### Своя инфраструктура (Docker Compose и т.п.)

Можно задать JDBC-URL напрямую:
```env
DATABASE_URL=jdbc:postgresql://db:5432/scrumpoker
DB_DRIVER=org.postgresql.Driver
# при необходимости креды отдельно:
# SPRING_DATASOURCE_USERNAME=...
# SPRING_DATASOURCE_PASSWORD=...
```

---

## OAuth2 — кабинет модератора

Анонимный вход (лобби → комната) работает **без** OAuth2.  
Кабинет модератора (`/account`) требует входа через Google или GitHub.

### Настройка Google OAuth

1. Откройте [Google Cloud Console](https://console.cloud.google.com/) → **APIs & Services** → **Credentials**
2. Нажмите **Create Credentials** → **OAuth client ID** → **Web application**
3. Добавьте в **Authorized redirect URIs**:
   ```
   https://poker.yourcompany.com/login/oauth2/code/google
   # Для локальной разработки:
   http://localhost:8080/login/oauth2/code/google
   ```
4. Скопируйте **Client ID** и **Client Secret** в `.env`:
   ```env
   GOOGLE_CLIENT_ID=your-client-id.apps.googleusercontent.com
   GOOGLE_CLIENT_SECRET=GOCSPX-...
   ```

### Настройка GitHub OAuth

1. Откройте [GitHub Settings → Developer settings → OAuth Apps](https://github.com/settings/developers)
2. Нажмите **New OAuth App**:
   - **Homepage URL:** `https://poker.yourcompany.com`
   - **Authorization callback URL:** `https://poker.yourcompany.com/login/oauth2/code/github`
3. Нажмите **Generate a new client secret**
4. Скопируйте в `.env`:
   ```env
   GITHUB_CLIENT_ID=Ov23li...
   GITHUB_CLIENT_SECRET=...
   ```

> **Без OAuth2 переменных** кнопки входа вернут ошибку от Google/GitHub. Само приложение (покер, комнаты) работает без изменений — участники по-прежнему анонимны.

---

## Логи и централизованный сбор (OpenSearch)

Приложение умеет писать **структурированные JSON-логи** (профиль `json`,
включён в `docker-compose.yml`). Опциональный стек сбора логов — OpenSearch +
OpenSearch Dashboards + Fluent Bit — поднимается отдельным compose-профилем:

```bash
# Поднять приложение вместе со стеком логирования
docker compose --profile logging up -d --build

# Dashboards (UI поиска и визуализации логов):
#   http://localhost:5601
```

**Как это работает:**

```
app (JSON в /app/logs/scrum-poker.json) → Fluent Bit → OpenSearch → Dashboards
```

- Логи приложения пишутся в общий том `app_logs`.
- `Fluent Bit` тейлит файл и отправляет события в индекс `scrum-poker-logs`.
- В Dashboards один раз создайте index pattern `scrum-poker-logs*`
  (Stack Management → Index Patterns), поле времени — `@timestamp`.

> ⚠️ В dev-стеке у OpenSearch **отключена безопасность**
> (`DISABLE_SECURITY_PLUGIN=true`). Для production включите security-плагин,
> TLS и аутентификацию, либо используйте управляемый OpenSearch/Elastic.

Без профиля `logging` приложение работает как обычно — логи просто пишутся
в файл и в stdout (`docker logs`).
