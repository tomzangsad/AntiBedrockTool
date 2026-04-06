@echo off
echo Building AntiBedrockTool...
echo.

if not exist "gradlew.bat" (
    echo [!] Gradle wrapper not found. Please run: gradle wrapper
    echo     Or download gradle wrapper files manually.
    echo     https://docs.gradle.org/current/userguide/gradle_wrapper.html
    pause
    exit /b 1
)

call gradlew.bat clean build

echo.
echo Build complete! JARs are in:
echo   spigot\build\libs\AntiBedrockTool-Spigot-2.0.0.jar
echo   velocity\build\libs\AntiBedrockTool-Velocity-2.0.0.jar
echo   bungeecord\build\libs\AntiBedrockTool-BungeeCord-2.0.0.jar
echo   geyser\build\libs\AntiBedrockTool-Geyser-2.0.0.jar  (Geyser Extension - put in Geyser extensions folder)
pause
