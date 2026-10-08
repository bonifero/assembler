@echo off
cd /d "%~dp0"
if not exist target\lab2-1.0.jar call mvn -q package
java -jar target\lab2-1.0.jar
