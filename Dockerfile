# ==========================================================
# AccessFlow - production image
#
# Two stages. Maven builds the executable Spring Boot jar; a JRE-only image
# runs it. Neither the build tooling, the Maven repository, nor the ~60 MB of
# intermediate output reaches the final image.
#
# The port is deliberately NOT set anywhere in this file. Render injects PORT,
# and application.properties already resolves it as
# `server.port=${PORT:8080}`, so the container listens on whatever Render routes
# to. Setting a port here would override that and the service would be reported
# unhealthy with nothing in the application log to explain it. There is no
# EXPOSE either, for the same reason - it would only be a hardcoded port that
# Render ignores.
#
# No profile is set, and that is deliberate. The local demo seeders
# (DevAdminSeedRunner, DevEmployeeSeedRunner) are @Profile("local"), and no
# profile is active unless one is asked for, so they cannot run in this image.
# Do not add SPRING_PROFILES_ACTIVE=local here: it would create a SUPER_ADMIN
# with a password published in the README.
#
# No credential appears in this file, and none may. The database is configured
# entirely through the environment at run time.
# ==========================================================


# ---------- build ----------

FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# pom.xml on its own first, so editing a source file does not re-resolve every
# dependency. The sources are copied afterwards and the jar is built from them.
COPY pom.xml .
RUN mvn -B -ntp -DskipTests dependency:go-offline

COPY src ./src

# -DskipTests: the database-backed test classes are gated on
# ACCESSFLOW_DB_PASSWORD, so in an image build they skip - but compiling and
# launching 400-odd tests to decide nothing is time the deploy should not pay.
RUN mvn -B -ntp -DskipTests package


# ---------- run ----------

FROM eclipse-temurin:21-jre

# Not root. The application needs a readable jar and a writable temp directory
# and nothing else, so it has no reason to own the container.
RUN groupadd --system --gid 1001 accessflow \
    && useradd --system --uid 1001 --gid accessflow \
       --home-dir /home/accessflow --create-home accessflow

WORKDIR /app

# The executable jar only. spring-boot-maven-plugin leaves a .jar.original
# beside it, which is the pre-repackage archive and not runnable; it does not
# match *.jar and is not copied. Renamed here so the run command does not
# carry the Maven version.
COPY --from=build /build/target/*.jar /app/accessflow.jar

USER accessflow:accessflow

# Exec form, so the JVM is PID 1 and receives SIGTERM itself. Render sends
# SIGTERM on every deploy, and this is what lets Spring Boot close the Hikari
# pool on the way out instead of having open connections cut underneath it.
ENTRYPOINT ["java", "-jar", "/app/accessflow.jar"]
