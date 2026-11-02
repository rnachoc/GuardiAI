# The policy service, built in a container like Mercadillo (ADR 4). Behind a TLS-intercepting proxy, pass
# MAVEN_OPTS and the build secret "maven_truststore" as compose.yaml explains.
#
# Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>

FROM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
ARG MAVEN_OPTS=""
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=maven_truststore,target=/opt/proxy-truststore,required=false \
    mvn --batch-mode --no-transfer-progress --strict-checksums -DskipTests package \
 && cp target/policy-0.5.0.jar /policy.jar

FROM eclipse-temurin:21.0.13_9-jre-alpine@sha256:82cf3d9ccbe1ed46252008d51b2f2329e6b9e8a2f4394c4bebefc3ea7f4b1372
# Runs as root on purpose: it is the one service that holds the Docker socket, to restart services (ADR 18).
COPY --from=build /policy.jar /opt/app/policy.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=90s --start-interval=2s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8080/audit/verify > /dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/opt/app/policy.jar"]
