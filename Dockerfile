FROM java:8-alpine
MAINTAINER Your Name <you@example.com>

ADD target/authfed-0.0.1-SNAPSHOT-standalone.jar /authfed/app.jar

EXPOSE 8080

CMD ["java", "-jar", "/authfed/app.jar"]
