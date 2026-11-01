@ECHO OFF
SETLOCAL
SET APP_HOME=%~dp0
IF DEFINED JAVA_HOME (
  SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
) ELSE (
  SET JAVA_EXE=java.exe
)
SET WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
IF EXIST "%WRAPPER_JAR%" (
  "%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
  EXIT /B %ERRORLEVEL%
)

REM Match the POSIX bootstrap: download the pinned distribution and verify SHA-256.
SET DIST_URL=https://services.gradle.org/distributions/gradle-8.9-bin.zip
SET DIST_SHA=d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab
IF DEFINED GRADLE_USER_HOME (
  SET BOOTSTRAP_DIR=%GRADLE_USER_HOME%\wrapper-bootstrap\gradle-8.9-bin
) ELSE (
  SET BOOTSTRAP_DIR=%APP_HOME%.gradle\wrapper-bootstrap\gradle-8.9-bin
)
SET ARCHIVE=%BOOTSTRAP_DIR%\gradle-8.9-bin.zip
SET GRADLE_BIN=%BOOTSTRAP_DIR%\gradle-8.9\bin\gradle.bat
SET OFFLINE=false
FOR %%A IN (%*) DO IF "%%~A"=="--offline" SET OFFLINE=true

IF NOT EXIST "%GRADLE_BIN%" (
  IF "%OFFLINE%"=="true" (
    ECHO Pinned Gradle 8.9 is not cached; --offline forbids downloading it. 1>&2
    ECHO Run gradlew.bat --version once with approved network access, then retry offline. 1>&2
    EXIT /B 2
  )
  IF NOT EXIST "%BOOTSTRAP_DIR%" MKDIR "%BOOTSTRAP_DIR%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%ARCHIVE%'; $actual=(Get-FileHash -Algorithm SHA256 '%ARCHIVE%').Hash.ToLowerInvariant(); if ($actual -ne '%DIST_SHA%') { throw 'Gradle distribution checksum mismatch' }; Expand-Archive -LiteralPath '%ARCHIVE%' -DestinationPath '%BOOTSTRAP_DIR%' -Force"
  IF ERRORLEVEL 1 EXIT /B 1
)

CALL "%GRADLE_BIN%" %*
EXIT /B %ERRORLEVEL%
