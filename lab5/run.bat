@echo off
cd /d "%~dp0"
if not exist target\lab5-1.0.jar call mvn -q package
java -jar target\lab5-1.0.jar
