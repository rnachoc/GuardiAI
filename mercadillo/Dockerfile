# One image per Mercadillo service, from a single shared build (ADR 4). Compose passes SERVICE.
# The build stage does not depend on SERVICE, so BuildKit runs Maven once for every service.
#
# Behind a TLS-intercepting proxy, pass MAVEN_OPTS with the proxy settings and provide the proxy's
# trust store as the build secret "maven_truststore" (see compose.yaml). Otherwise both stay unused.
#
# Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>

FROM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
ARG MAVEN_OPTS=""
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=maven_truststore,target=/opt/proxy-truststore,required=false \
    mvn --batch-mode --no-transfer-progress --strict-checksums -DskipTests package \
 && mkdir /out \
 && for service in gateway catalog cart checkout payments inventory notifier; do \
      cp "$service/target/$service.jar" "/out/$service.jar"; \
    done \
 && cp otel-agent/target/opentelemetry-javaagent.jar /out/opentelemetry-javaagent.jar

FROM eclipse-temurin:21.0.13_9-jre-alpine@sha256:82cf3d9ccbe1ed46252008d51b2f2329e6b9e8a2f4394c4bebefc3ea7f4b1372
ARG SERVICE
RUN test -n "$SERVICE" || (echo "build with --build-arg SERVICE=<name>" >&2; exit 1) \
 && addgroup -S mercadillo && adduser -S -G mercadillo mercadillo
COPY --from=build /out/opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar
COPY --from=build /out/${SERVICE}.jar /opt/app/app.jar
USER mercadillo
ENV OTEL_SERVICE_NAME=${SERVICE} \
    JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar -XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=120s --start-interval=2s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8080/actuator/health/readiness | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "/opt/app/app.jar"]
