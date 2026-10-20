# 办公 Demo Java 后端（Spring Boot）

FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src

FROM build AS test
RUN mvn -B test

FROM test AS package
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=package /build/target/office-product-import-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
