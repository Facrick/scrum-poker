# ── Stage 1: сборка ─────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# Кэшируем зависимости отдельным слоем
RUN mvn dependency:go-offline -q
COPY src ./src
# maven.test.skip=true — не компилируем и не гоняем тесты в прод-сборке
# (исключает тяжёлые тестовые зависимости вроде Playwright из сборки образа)
RUN mvn package -Dmaven.test.skip=true -q

# ── Stage 2: минимальный runtime ────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# Непривилегированный пользователь
RUN addgroup -S poker && adduser -S poker -G poker
COPY --from=build /app/target/*.jar app.jar
# Каталог логов с правами poker — именованный том унаследует владельца
RUN mkdir -p /app/logs && chown -R poker:poker /app/logs
USER poker
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
