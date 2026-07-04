# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp -DskipTests -Pproduction package

FROM eclipse-temurin:21-jre
WORKDIR /app
# Activate the production profile so OwnerBootstrap refuses to seed with the dev fallback hash.
ENV SPRING_PROFILES_ACTIVE=production
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
