FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY . .
RUN mvn -q package -DskipTests
FROM eclipse-temurin:17-jre
COPY --from=build /app/target/venturelink-1.0.jar /app.jar
ENV PORT=8080
CMD ["sh","-c","java -Dserver.port=$PORT -jar /app.jar"]
