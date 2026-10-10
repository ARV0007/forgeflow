FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /gateway
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /gateway
RUN useradd --create-home --shell /bin/false gateway
USER gateway
COPY --from=build /gateway/target/*.jar gateway.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "gateway.jar"]
