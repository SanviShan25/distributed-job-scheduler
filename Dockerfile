# 1. The web app (web/), built with Node. Only this stage needs Node; the jar build and the runtime image don't.
FROM node:24.22.3-bookworm-slim AS web

WORKDIR /web
# Dependencies first, so this layer is reused until package.json / package-lock.json change.
COPY web/package.json web/package-lock.json web/.npmrc ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# 2. The Spring Boot jar, with the web app as its static files (served by WebAppConfig).
FROM maven:3.9.16-eclipse-temurin-25 AS build

WORKDIR /workspace
COPY pom.xml .
COPY src ./src
COPY --from=web /web/dist ./src/main/resources/static
RUN mvn -B -DskipTests package

# 3. Runtime: just the JRE and the jar.
FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
