# File: Dockerfile
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY target/observability-service-1.0.0.jar app.jar

EXPOSE 8080

ENV JAVA_OPTS="-Xms512m -Xmx1024m"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]