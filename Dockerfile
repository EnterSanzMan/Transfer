# --- Compilación ---
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B package -DskipTests

# --- Ejecución ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S transfer && adduser -S transfer -G transfer \
    && mkdir -p /app/data /app/uploads && chown -R transfer:transfer /app

COPY --from=build /src/target/transfer-1.0.0.jar app.jar

ENV PORT=8080 \
    DB_PATH=/app/data/transfer.db \
    UPLOAD_DIR=/app/uploads

USER transfer
EXPOSE 8080
VOLUME ["/app/data", "/app/uploads"]

ENTRYPOINT ["java", "-Xmx256m", "-jar", "app.jar"]
