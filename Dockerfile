# syntax=docker/dockerfile:1

FROM maven:3.9.16-eclipse-temurin-21-noble AS build

WORKDIR /workspace

COPY pom.xml .
COPY frontend ./frontend
COPY src ./src
# BuildKit preserves resolved artifacts without dependency:go-offline traversing invalid snapshot metadata.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Dmaven.test.skip=true package \
    && cp target/endervault-nas-*.jar /workspace/endervault.jar

FROM eclipse-temurin:21-jre-noble AS runtime

ARG ENDERVAULT_UID=10001
ARG ENDERVAULT_GID=10001

RUN groupadd --gid "${ENDERVAULT_GID}" endervault \
    && useradd \
        --uid "${ENDERVAULT_UID}" \
        --gid "${ENDERVAULT_GID}" \
        --home-dir /var/lib/endervault \
        --create-home \
        --shell /usr/sbin/nologin \
        endervault \
    && install -d -o endervault -g endervault /opt/endervault

COPY --from=build --chown=endervault:endervault /workspace/endervault.jar /opt/endervault/endervault.jar

USER endervault:endervault
WORKDIR /var/lib/endervault

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/opt/endervault/endervault.jar"]
