@echo off
cd /d "%~dp0"
if not exist target\lab6-1.0.jar call mvn -q package
java -jar target\lab6-1.0.jar
