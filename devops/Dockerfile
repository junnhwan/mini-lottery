FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY app.jar app.jar

# 设置中国时区
ENV TZ=Asia/Shanghai

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
