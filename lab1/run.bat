@echo off
cd /d "%~dp0"
if not exist target\lab1-1.0.jar call mvn -q package
java -jar target\lab1-1.0.jar
