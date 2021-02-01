FROM openjdk:11.0-jdk-slim-buster

COPY target/*.jar /app.jar

EXPOSE 8080

VOLUME /tmp

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app.jar"]
