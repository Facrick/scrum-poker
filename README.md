# Scrum Poker

Инструмент для командной оценки задач в реальном времени по методологии Planning Poker.  
Не требует регистрации. До 100 участников в комнате.

---

## Содержание

1. [Возможности](#возможности)
2. [Технологии](#технологии)
3. [Архитектура](#архитектура)
4. [Запуск локально](#запуск-локально)
5. [Деплой](#деплой)
6. [Конфигурация](#конфигурация)
7. [Руководство пользователя](#руководство-пользователя)
8. [WebSocket API](#websocket-api)
9. [Структура проекта](#структура-проекта)

---

## Возможности

### Голосование
- Создание комнаты без регистрации — достаточно указать имя
- Роли: **Модератор** (первый вошедший), **Участник** (голосует), **Наблюдатель** (смотрит без голосования)
- Карты скрыты до вскрытия — участники не видят чужие голоса
- Визуальный прогресс-бар голосования

### Колоды карт
| Колода | Значения |
|--------|----------|
| Фибоначчи | 0, 1, 2, 3, 5, 8, 13, 21, ?, ☕ |
| Степени двойки | 1, 2, 4, 8, 16, 32, ?, ☕ |
| Майки (T-Shirt) | XS, S, M, L, XL, ?, ☕ |
| Своя колода | До 20 произвольных значений |

### Результаты
- Автоматическое определение консенсуса
- Статистика: среднее, медиана, распределение голосов
- Фиксация итоговой оценки модератором
- Кнопка «Переголосовать» при отсутствии консенсуса

### Бэклог
- Добавление задач прямо из панели модератора
- Активация задачи автоматически запускает новый раунд
- Оценки сохраняются рядом с каждой задачей
- Экспорт всего бэклога в `.xlsx` (Excel)

### Таймер
- Обратный отсчёт: 30 сек / 1 мин / 1:30 / 2 мин / 3 мин
- Автоматическое вскрытие карт по истечении (только у модератора)
- Пульсирующая индикация при ≤10 секундах

### Прочее
- Приглашение по ссылке (кнопка «🔗 Пригласить»)
- Удаление участника модератором
- Адаптивный дизайн (мобильные устройства)
- Автоматическое переподключение при разрыве связи

---

## Технологии

### Бэкенд
| Технология | Версия | Назначение |
|------------|--------|------------|
| Java | 17 | Язык |
| Spring Boot | 3.2.5 | Фреймворк |
| Spring WebSocket + STOMP | — | Реал-тайм связь |
| SockJS | — | Fallback-транспорт для WebSocket |
| Maven | 3.9 | Сборка |

### Фронтенд
| Технология | Назначение |
|------------|------------|
| Vanilla JS (ES2020) | Без фреймворков |
| STOMP.js 7 | WebSocket-клиент |
| SockJS-client 1 | Транспорт |
| SheetJS (xlsx) | Экспорт в Excel |
| Inter (Google Fonts) | Шрифт |

### Инфраструктура
| Компонент | Назначение |
|-----------|------------|
| Docker (multi-stage) | Контейнеризация |
| nginx | Reverse proxy, HTTPS |
| Let's Encrypt | SSL-сертификат |
| Railway / VPS | Хостинг |

---

## Архитектура

```
Браузер
  │
  ├── HTTP GET /               → index.html, style.css, app.js (статика)
  ├── HTTP POST /api/rooms     → создать комнату
  ├── HTTP GET  /api/rooms/{id}→ проверить существование
  └── WebSocket /ws (STOMP over SockJS)
        │
        ├── /app/room/{id}/join            → войти в комнату
        ├── /app/room/{id}/vote            → проголосовать
        ├── /app/room/{id}/reveal          → вскрыть карты (мод.)
        ├── /app/room/{id}/reset           → новый раунд (мод.)
        ├── /app/room/{id}/story           → задать задачу (мод.)
        ├── /app/room/{id}/deck            → сменить колоду (мод.)
        ├── /app/room/{id}/customdeck      → своя колода (мод.)
        ├── /app/room/{id}/estimate        → зафиксировать оценку (мод.)
        ├── /app/room/{id}/timer/start     → запустить таймер (мод.)
        ├── /app/room/{id}/timer/stop      → остановить таймер (мод.)
        ├── /app/room/{id}/backlog/add     → добавить задачу в бэклог (мод.)
        ├── /app/room/{id}/backlog/remove  → удалить задачу (мод.)
        ├── /app/room/{id}/backlog/activate→ активировать задачу (мод.)
        └── /app/room/{id}/kick            → кикнуть участника (мод.)

Сервер
  ├── In-memory хранилище (ConcurrentHashMap)
  ├── Рассылка: /topic/room/{id}  → всем участникам комнаты
  ├── Личные: /user/queue/me      → конкретному клиенту
  └── Автоочистка: каждые 30 мин, TTL = 8 ч (настраивается)
```

### Состояние комнаты
Хранится в памяти. При перезапуске сервера комнаты теряются. Каждое изменение рассылается всем участникам как `RoomStateDto`.

### Роли
| Роль | Голосует | Управляет комнатой | Видит чужие голоса до вскрытия |
|------|----------|--------------------|-------------------------------|
| MODERATOR | Нет | Да | Нет |
| PLAYER | Да | Нет | Нет |
| OBSERVER | Нет | Нет | Нет |

Первый вошедший в комнату автоматически становится модератором.

---

## Запуск локально

### Требования
- Java 17+
- Maven 3.9+

### Запуск через Maven
```bash
git clone https://github.com/Facrick/scrum-poker.git
cd scrum-poker
mvn spring-boot:run
# Открыть http://localhost:8080
```

### Сборка JAR
```bash
mvn package -DskipTests
java -jar target/scrum-poker-0.1.0.jar
```

### Через Docker
```bash
docker build -t scrum-poker .
docker run -p 8080:8080 -e ALLOWED_ORIGIN=http://localhost:8080 scrum-poker
```

---

## Деплой

Подробная пошаговая инструкция: [DEPLOY.md](DEPLOY.md)

### Railway (быстрый старт)
1. Форкнуть репозиторий на GitHub
2. Создать проект на [railway.app](https://railway.app) → Deploy from GitHub repo
3. Variables → добавить `ALLOWED_ORIGIN = *`
4. Settings → Networking → Generate Domain

### Docker Compose (собственный сервер)
```bash
cp .env.example .env
# Отредактировать .env: вписать домен
docker compose up -d --build
```

---

## Конфигурация

| Переменная окружения | По умолчанию | Описание |
|----------------------|--------------|----------|
| `ALLOWED_ORIGIN` | `http://localhost:8080` | Разрешённый origin для WebSocket. `*` = все домены |
| `ROOM_TTL_HOURS` | `8` | Через сколько часов удалять неактивные комнаты |

Пример `.env`:
```env
ALLOWED_ORIGIN=https://poker.yourcompany.com
ROOM_TTL_HOURS=8
```

---

## Руководство пользователя

### Создание комнаты
1. Открыть приложение
2. Ввести своё имя
3. Указать название сессии (например, «Спринт 42»)
4. Нажать **Создать комнату**
5. Поделиться ссылкой через кнопку **🔗 Пригласить**

### Вход в комнату по ссылке
1. Открыть ссылку-приглашение
2. Ввести имя
3. Выбрать роль: Участник (голосует) или Наблюдатель
4. Нажать **Войти в комнату**

### Процесс оценки одной задачи

```
Модератор:
  1. Ввести название задачи → нажать «Задать»
     (задача добавляется в бэклог и становится активной)
  2. При необходимости сменить колоду карт
  3. При необходимости установить таймер → «▶ Старт»

Участники:
  4. Выбрать карту с оценкой (клик по карте внизу экрана)

Модератор:
  5. Нажать «Вскрыть карты»
  6a. Консенсус → нажать «Зафиксировать» (или изменить значение вручную)
  6b. Нет консенсуса → обсудить, нажать «🔄 Переголосовать»
  7. Нажать «Новый раунд» для следующей задачи
```

### Бэклог
- Все добавленные задачи отображаются в панели **📋 Бэклог** (правая сторона экрана)
- Нажать на задачу — активировать её для голосования
- После фиксации оценки она появляется рядом с названием задачи
- Кнопка **⬇ XLS** — скачать весь бэклог с оценками в Excel

### Своя колода
1. В панели модератора выбрать **Своя колода…**
2. Ввести значения через запятую: `S, M, L, XL, XXL`
3. Нажать **Применить**

---

## WebSocket API

### Подключение
```
Endpoint:  /ws  (SockJS fallback)
Protocol:  STOMP 1.1 / 1.2

Подписки:
  /user/queue/me        — личные сообщения (ответ на join, ошибки)
  /topic/room/{roomId}  — обновления состояния комнаты (всем участникам)
```

### Входящие сообщения (клиент → сервер)

Отправляются на `/app/room/{roomId}/{action}`, тело — JSON.

#### join
```json
{ "name": "Алиса", "role": "PLAYER" }
```
Ответ на `/user/queue/me`:
```json
{ "participantId": "abc12345", "role": "MODERATOR" }
```
При ошибке: `{ "error": "Комната заполнена (максимум 100)" }`

#### vote
```json
{ "participantId": "abc12345", "value": "5" }
```
Игнорируется если значение отсутствует в текущей колоде или карты уже вскрыты.

#### reveal
```json
{ "participantId": "abc12345" }
```

#### reset
```json
{ "participantId": "abc12345" }
```
Сбрасывает голоса, скрывает карты, очищает итоговую оценку. История задачи сохраняется.

#### story
```json
{ "participantId": "abc12345", "story": "Название задачи" }
```
Добавляет задачу в бэклог, активирует её, сбрасывает раунд.

#### deck
```json
{ "participantId": "abc12345", "deck": "FIBONACCI" }
```
Значения: `FIBONACCI`, `POWERS_OF_TWO`, `TSHIRT`. Для своей колоды использовать `customdeck`.

#### customdeck
```json
{ "participantId": "abc12345", "cards": ["S", "M", "L", "XL", "?"] }
```
До 20 карт, каждая до 8 символов.

#### estimate
```json
{ "participantId": "abc12345", "estimate": "8" }
```
Фиксирует итоговую оценку. Доступно только после вскрытия карт.

#### timer/start
```json
{ "participantId": "abc12345", "seconds": 60 }
```

#### timer/stop
```json
{ "participantId": "abc12345" }
```

#### backlog/add
```json
{ "participantId": "abc12345", "title": "Название задачи" }
```

#### backlog/remove
```json
{ "participantId": "abc12345", "itemId": "uuid-строка" }
```

#### backlog/activate
```json
{ "participantId": "abc12345", "itemId": "uuid-строка" }
```
Устанавливает задачу как текущую, сбрасывает раунд.

#### kick
```json
{ "participantId": "abc12345", "targetId": "def67890" }
```

### Исходящее сообщение — RoomStateDto

Рассылается всем на `/topic/room/{roomId}` после каждого изменения:

```json
{
  "roomId": "abc12345",
  "roomName": "Спринт 42",
  "deck": "FIBONACCI",
  "cards": ["0","1","2","3","5","8","13","21","?","☕"],
  "currentStory": "PROJ-101: Добавить авторизацию",
  "revealed": false,
  "participants": [
    {
      "id": "abc12345",
      "name": "Алиса",
      "role": "MODERATOR",
      "online": true,
      "hasVoted": false,
      "vote": null
    },
    {
      "id": "def67890",
      "name": "Борис",
      "role": "PLAYER",
      "online": true,
      "hasVoted": true,
      "vote": null
    }
  ],
  "stats": null,
  "finalEstimate": null,
  "timerStartedAt": 1700000000000,
  "timerSeconds": 60,
  "backlog": [
    { "id": "uuid-1", "title": "PROJ-101: Добавить авторизацию", "estimate": null },
    { "id": "uuid-2", "title": "PROJ-102: Рефакторинг API", "estimate": "5" }
  ],
  "activeItemId": "uuid-1"
}
```

**Примечания:**
- `vote` в `participants` заполняется только при `revealed: true`
- `stats` заполняется только при `revealed: true`:
  ```json
  {
    "average": 6.5,
    "median": 5.0,
    "consensus": false,
    "distribution": { "5": 3, "8": 2 }
  }
  ```
- `timerStartedAt` — Unix timestamp в миллисекундах, `null` если таймер не запущен

---

## Структура проекта

```
scrum-poker/
├── src/main/
│   ├── java/com/scrumpoker/
│   │   ├── ScrumPokerApplication.java     — точка входа, @EnableScheduling
│   │   ├── config/
│   │   │   └── WebSocketConfig.java       — STOMP endpoints, CORS
│   │   ├── controller/
│   │   │   └── RoomController.java        — REST API (/api/rooms)
│   │   ├── dto/
│   │   │   └── RoomStateDto.java          — снимок состояния + статистика
│   │   ├── model/
│   │   │   ├── BacklogItem.java            — задача бэклога (id, title, estimate)
│   │   │   ├── Deck.java                  — колоды (FIBONACCI, POWERS_OF_TWO, TSHIRT, CUSTOM)
│   │   │   ├── Participant.java           — участник (id, name, role, vote, online)
│   │   │   └── Room.java                  — состояние комнаты, resetRound()
│   │   ├── service/
│   │   │   └── RoomService.java           — CRUD комнат + @Scheduled TTL-очистка
│   │   └── ws/
│   │       ├── DisconnectListener.java    — SessionDisconnectEvent → handleDisconnect()
│   │       ├── Messages.java              — record-типы входящих STOMP-сообщений
│   │       └── PokerController.java       — @MessageMapping обработчики
│   └── resources/
│       ├── application.properties
│       └── static/
│           ├── index.html                 — разметка (лобби + комната)
│           ├── style.css                  — дизайн-система (CSS custom properties)
│           └── app.js                     — вся логика фронтенда
├── nginx/
│   └── nginx.conf                         — HTTPS, WS proxy, rate limiting
├── Dockerfile                             — multi-stage: Maven → JRE Alpine
├── docker-compose.yml                     — app + nginx сервисы
├── .env.example                           — шаблон переменных окружения
├── DEPLOY.md                              — инструкция по деплою
└── pom.xml
```
