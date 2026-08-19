# Builds either deployable in this repo; pick one with --build-arg MODULE=...
#   docker build --build-arg MODULE=mcp-gateway-server .
# The build stage compiles from source so the image is reproducible from a clean checkout with
# nothing but Docker installed.
FROM maven:3.9-eclipse-temurin-17 AS build
ARG MODULE=mcp-gateway-server
WORKDIR /build

# Copy the poms first so dependency resolution is cached separately from source changes.
COPY pom.xml ./
COPY mcp-gateway-core/pom.xml mcp-gateway-core/
COPY mcp-gateway-autoconfigure/pom.xml mcp-gateway-autoconfigure/
COPY mcp-gateway-spring-boot-starter/pom.xml mcp-gateway-spring-boot-starter/
COPY mcp-gateway-sample/pom.xml mcp-gateway-sample/
COPY mcp-gateway-server/pom.xml mcp-gateway-server/
COPY mcp-gateway-demo-upstream/pom.xml mcp-gateway-demo-upstream/
RUN mvn -B -ntp -pl ${MODULE} -am dependency:go-offline

COPY . .
RUN mvn -B -ntp -pl ${MODULE} -am -DskipTests package \
    && cp ${MODULE}/target/${MODULE}.jar /build/app.jar

FROM eclipse-temurin:17-jre
RUN useradd --system --create-home --uid 10001 gateway
WORKDIR /app
COPY --from=build --chown=gateway:gateway /build/app.jar /app/app.jar
USER gateway
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
