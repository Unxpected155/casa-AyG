# syntax=docker/dockerfile:1
# Imagen de Casa A&G (Fase 7 / Docker D4–D5). Tres etapas:
#   1. frontend: compila la SPA con Node (Node NO llega a la imagen final).
#   2. build:    compila el backend con el JDK y empaqueta la SPA dentro del jar.
#   3. runtime:  solo el JRE + el jar, con un usuario sin privilegios.
# Bases fijadas por digest (índice multi-arquitectura: amd64 para probar, arm64 para Oracle).

FROM node:25-bookworm-slim@sha256:81db02c4b671288a03915da9534dbd54f96d0e7c24d80ccc54f5b36b2e684370 AS frontend
WORKDIR /build/frontend
# Primero solo los manifiestos: la capa de dependencias se reutiliza si el código cambia.
COPY frontend/package.json frontend/package-lock.json frontend/.npmrc ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:25-jdk-noble@sha256:80f67fb3d6f76e2d817d98a4cc2a9cf05f054d4e6adc5e2144bde671d722e63f AS build
WORKDIR /build
# El wrapper de Maven necesita curl y unzip para bajar Maven (y verifica su SHA-256).
# Solo en esta etapa: no llegan a la imagen final.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip \
 && rm -rf /var/lib/apt/lists/*
COPY backend/.mvn backend/.mvn
COPY backend/mvnw backend/pom.xml backend/
RUN ./backend/mvnw -B -ntp -q -f backend/pom.xml dependency:go-offline
COPY backend/src backend/src
COPY --from=frontend /build/frontend/dist frontend/dist
# Los tests corren en el CI (con Postgres real); aquí solo se empaqueta.
RUN ./backend/mvnw -B -ntp -q -f backend/pom.xml package -DskipTests \
 && cp backend/target/casa-ayg-*.jar /build/app.jar

FROM eclipse-temurin:25-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c AS runtime
# Usuario fijo sin privilegios y sin shell de login. El jar es de root y de solo lectura:
# si alguien lograra ejecutar código en la app, no puede modificarla.
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build --chown=root:root --chmod=0444 /build/app.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
# La JVM usa como máximo el 70 % de la memoria que el contenedor tenga asignada.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
