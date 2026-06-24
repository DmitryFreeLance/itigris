# === Stage 1: build ===
FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /app

# Сначала pom —> кэш зависимостей
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline

# Исходники
COPY src ./src

# Сборка
RUN mvn -B -DskipTests clean package

# Копируем собранный JAR в предсказуемое место
# (берём fat-jar, если есть, иначе первый обычный .jar)
RUN set -eux; \
    FAT="$(ls target/*-with-dependencies.jar 2>/dev/null || true)"; \
    if [ -n "$FAT" ]; then cp "$FAT" /tmp/app.jar; \
    else cp "$(ls target/*.jar | head -n1)" /tmp/app.jar; fi


# === Stage 2: runtime ===
FROM eclipse-temurin:21-jre-jammy
WORKDIR /opt/order-bot

# (опционально) корректные TZ в образе
RUN apt-get update && apt-get install -y --no-install-recommends tzdata && rm -rf /var/lib/apt/lists/*

# Сертификаты Минцифры для доступа к MAX API.
COPY certs/ /certificates/

# Кладём JAR
COPY --from=build /tmp/app.jar ./app.jar

# Том для SQLite БД
VOLUME ["/data"]

# Значения по умолчанию (можно переопределить через -e)
ENV DB_PATH=/data/bot.db \
    TZ=Asia/Yekaterinburg \
    SCAN_INTERVAL_MINUTES=15 \
    REMINDER_WINDOW_MINUTES=60 \
    REMINDER_LEAD_HOURS=72 \
    USE_SYSTEM_CA_CERTS=1 \
    JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false"

# Эти задаются при запуске:
# - BOT_TOKEN
# - один из вариантов интеграции с Itigris:
#   legacy:
#   - ITIGRIS_CLIENT
#   - ITIGRIS_API_KEY
#   v2:
#   - ITIGRIS_V2_COMPANY
#   - ITIGRIS_V2_LOGIN
#   - ITIGRIS_V2_PASSWORD
#   - ITIGRIS_V2_DEPARTMENT_ID

# Если планируете healthcheck — можно добавить curl/jq и тут прописать проверку

# Запуск
ENTRYPOINT ["/__cacert_entrypoint.sh","java","-jar","/opt/order-bot/app.jar"]
