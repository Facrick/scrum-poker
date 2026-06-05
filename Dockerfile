# ── Stage 1: сборка ─────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# Кэшируем зависимости отдельным слоем
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: минимальный runtime ────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# Непривилегированный пользователь
RUN addgroup -S poker && adduser -S poker -G poker
COPY --from=build /app/target/*.jar app.jar
USER poker
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
