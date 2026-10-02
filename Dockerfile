# =========================
# Stage 1: Build application
# =========================
FROM eclipse-temurin:17-jdk AS build

WORKDIR /app

COPY . .

RUN chmod +x mvnw
RUN ./mvnw clean package -DskipTests


# =========================
# Stage 2: Run application
# =========================
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"

ENTRYPOINT ["java", "-jar", "app.jar"]