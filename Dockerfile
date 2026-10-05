# ---- build stage ----
# Multi-stage so the runtime image ships only a JRE and the jar, not Maven, the JDK
# and the ~100MB local repository.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Optional: trust extra CAs so Maven works behind TLS-inspecting proxies (see certs/README.md).
# A no-op when the folder holds no certificates, so normal networks are unaffected.
COPY certs/ /tmp/extra-certs/
RUN for c in /tmp/extra-certs/*.crt /tmp/extra-certs/*.pem; do \
      [ -f "$c" ] && keytool -importcert -noprompt -cacerts -storepass changeit -alias "$(basename "$c")" -file "$c"; \
    done; true

# Copy the pom on its own first: Docker caches this layer, so dependencies are only
# re-downloaded when pom.xml changes, not on every source edit.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package

# ---- runtime stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app
# Don't run as root inside the container.
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /build/target/chat.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
