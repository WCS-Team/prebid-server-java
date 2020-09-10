FROM openjdk:8-jdk-alpine
VOLUME /tmp
EXPOSE 80
ADD target/prebid-server.jar app.jar
COPY prebid-config.yaml /prebid-config.yaml
COPY sample-app-settings.yaml /sample-app-settings.yaml
ENTRYPOINT ["java", "-jar","app.jar", "--spring.config.additional-location=file:./prebid-config.yaml"]

