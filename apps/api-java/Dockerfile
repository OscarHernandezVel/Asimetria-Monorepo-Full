# syntax=docker/dockerfile:1.7
# Imagen de la API Java (RNF-10). Multi-etapa: Maven compila; la imagen final solo lleva JRE + app,
# corre sin root y no contiene código fuente ni herramientas de build.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
# Las pruebas (Testcontainers) corren en CI con `mvn verify`; aquí solo se empaqueta.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests -Djacoco.skip=true \
 && java -Djarmode=tools -jar target/asimetria-api.jar extract --destination /app

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
# CA de Amazon RDS: la conexión a PostgreSQL verifica el certificado (sslmode=verify-full en AWS).
ADD --chmod=444 https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem /app/rds-ca.pem
# Dependencias y aplicación en capas separadas: un cambio de código solo sube la capa pequeña a ECR.
COPY --from=build /app/lib ./lib
COPY --from=build /app/asimetria-api.jar ./
USER app
EXPOSE 8000
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
# Vivacidad sin depender de la BD (la disponibilidad completa la mide el ALB con /actuator/health/readiness).
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=4 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8000 && printf "GET /actuator/health/liveness HTTP/1.0\r\n\r\n" >&3 && head -1 <&3 | grep -q " 200"'
ENTRYPOINT ["java", "-jar", "asimetria-api.jar"]
