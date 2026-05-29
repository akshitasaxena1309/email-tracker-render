FROM eclipse-temurin:17-jdk-alpine
 
WORKDIR /app
 
COPY src/EmailTracker.java .
 
RUN javac EmailTracker.java
 
EXPOSE 8080
 
CMD ["java", "EmailTracker"]
