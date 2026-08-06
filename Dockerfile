FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
RUN mkdir -p /app/data/images /app/data/videos
COPY target/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
