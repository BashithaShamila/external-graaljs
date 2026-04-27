@echo off

REM ---------------------------------------------------------------------------
REM  Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
REM
REM  WSO2 LLC. licenses this file to you under the Apache License,
REM  Version 2.0 (the "License"); you may not use this file except
REM  in compliance with the License.
REM  You may obtain a copy of the License at
REM
REM      http://www.apache.org/licenses/LICENSE-2.0
REM
REM  Unless required by applicable law or agreed to in writing, software
REM  distributed under the License is distributed on an "AS IS" BASIS,
REM  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
REM  See the License for the specific language governing permissions and
REM  limitations under the License.
REM ---------------------------------------------------------------------------

rem ---------------------------------------------------------------------------
rem Main script for the WSO2 Identity GraalJS Runtime.
rem
rem Environment Variable Prerequisites
rem
rem   GRAALJS_RUNTIME_HOME   Home of the runtime installation. If not set, the
rem                          script derives it from its own location.
rem
rem   JAVA_HOME              Must point at a JDK 11–21 installation.
rem
rem   JVM_MEM_OPTS           (Optional) Heap sizing for the JVM.
rem
rem   JAVA_OPTS              (Optional) Additional JVM options.
rem ---------------------------------------------------------------------------

:checkJava
if "%JAVA_HOME%" == "" goto noJavaHome
if not exist "%JAVA_HOME%\bin\java.exe" goto noJavaHome
goto checkServer

:noJavaHome
echo "You must set the JAVA_HOME variable before running the GraalJS Runtime."
goto end

:checkServer
setlocal enabledelayedexpansion
if "%GRAALJS_RUNTIME_HOME%"=="" set GRAALJS_RUNTIME_HOME=%~sdp0..
SET curDrive=%cd:~0,1%
SET targetDrive=%GRAALJS_RUNTIME_HOME:~0,1%
if not "%curDrive%" == "%targetDrive%" %targetDrive%:
goto updateClasspath

:updateClasspath
cd %GRAALJS_RUNTIME_HOME%

rem ---- Resolve where the runtime jars live --------------------------------
rem Extracted distribution: lib\*.jar
rem Source tree (after mvn package): target\lib\*.jar + target\*.jar
set RUNTIME_LIB_DIR=
set RUNTIME_MAIN_JAR_DIR=
if exist "%GRAALJS_RUNTIME_HOME%\lib\*.jar" set RUNTIME_LIB_DIR=%GRAALJS_RUNTIME_HOME%\lib
if "%RUNTIME_LIB_DIR%"=="" if exist "%GRAALJS_RUNTIME_HOME%\target\lib\*.jar" (
    set RUNTIME_LIB_DIR=%GRAALJS_RUNTIME_HOME%\target\lib
    set RUNTIME_MAIN_JAR_DIR=%GRAALJS_RUNTIME_HOME%\target
)
if "%RUNTIME_LIB_DIR%"=="" (
    echo Error: cannot locate runtime jars.
    echo   Looked under %GRAALJS_RUNTIME_HOME%\lib and %GRAALJS_RUNTIME_HOME%\target\lib.
    echo   Build with 'mvn clean install' or extract the distribution archive first.
    goto end
)

rem conf\ must come before the jar entries so slf4j-simple / log4j2 can locate
rem their configuration files on the classpath.
set RUNTIME_CLASSPATH=".\conf"
if not "%RUNTIME_MAIN_JAR_DIR%"=="" (
    FOR %%R in ("%RUNTIME_MAIN_JAR_DIR%\wso2is-graaljs-runtime-*.jar") DO (
        echo %%~nR | findstr /B "original-" >nul || set RUNTIME_CLASSPATH=!RUNTIME_CLASSPATH!;"%%R"
    )
)
FOR %%C in ("%RUNTIME_LIB_DIR%\*.jar") DO set RUNTIME_CLASSPATH=!RUNTIME_CLASSPATH!;"%%C"

rem ----- Process the input command -------------------------------------------
:setupArgs
if ""%1""=="""" goto doneStart

if ""%1""==""-run""     goto commandLifecycle
if ""%1""==""--run""    goto commandLifecycle
if ""%1""==""run""      goto commandLifecycle

if ""%1""==""-restart""  goto commandLifecycle
if ""%1""==""--restart"" goto commandLifecycle
if ""%1""==""restart""   goto commandLifecycle

if ""%1""==""debug""    goto commandDebug
if ""%1""==""-debug""   goto commandDebug
if ""%1""==""--debug""  goto commandDebug

if ""%1""==""version""   goto commandVersion
if ""%1""==""-version""  goto commandVersion
if ""%1""==""--version"" goto commandVersion

shift
goto setupArgs

:commandVersion
shift
type "%GRAALJS_RUNTIME_HOME%\bin\version.txt"
goto end

:commandDebug
shift
set DEBUG_PORT=%1
if "%DEBUG_PORT%"=="" goto noDebugPort
if not "%JAVA_OPTS%"=="" echo Warning !!!. User specified JAVA_OPTS will be ignored, once you give the --debug option.
set JAVA_OPTS=-Xdebug -Xnoagent -Djava.compiler=NONE -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=%DEBUG_PORT%
echo Please start the remote debugging client to continue...
goto findJdk

:noDebugPort
echo Please specify the debug port after the --debug option
goto end

:commandLifecycle
goto findJdk

:doneStart
if "%OS%"=="Windows_NT" @setlocal
if "%OS%"=="WINNT" @setlocal

:findJdk
set CMD=RUN %*

:checkJdk21
PATH %PATH%;%JAVA_HOME%\bin\
for /f tokens^=2-5^ delims^=.-_^" %%j in ('java -fullversion 2^>^&1') do set "JAVA_VERSION=%%j%%k"
if %JAVA_VERSION% LSS 110 goto unknownJdk
if %JAVA_VERSION% GTR 210 goto unknownJdk
goto runServer

:unknownJdk
echo Starting GraalJS Runtime (in unsupported JDK)
echo [ERROR] GraalJS Runtime is supported only between JDK 11 and JDK 21
goto runServer

:runServer
cd %GRAALJS_RUNTIME_HOME%

if "%JVM_MEM_OPTS%"=="" set JVM_MEM_OPTS=-Xms256m -Xmx512m

set JAVA_VER_BASED_OPTS=--add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.security=ALL-UNNAMED --add-opens=java.base/sun.security.util=ALL-UNNAMED

set CMD_LINE_ARGS=%JVM_MEM_OPTS% -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath="%GRAALJS_RUNTIME_HOME%\repository\logs\heap-dump.hprof" %JAVA_OPTS% -classpath %RUNTIME_CLASSPATH% %JAVA_VER_BASED_OPTS% -Dgraaljs.runtime.home="%GRAALJS_RUNTIME_HOME%" -Dconf.location="%GRAALJS_RUNTIME_HOME%\conf" -Djava.io.tmpdir="%GRAALJS_RUNTIME_HOME%\tmp" -Dfile.encoding=UTF8 -Djava.net.preferIPv4Stack=true

:runJava
echo JAVA_HOME environment variable is set to %JAVA_HOME%
echo GRAALJS_RUNTIME_HOME environment variable is set to %GRAALJS_RUNTIME_HOME%
"%JAVA_HOME%\bin\java" %CMD_LINE_ARGS% org.wso2.carbon.identity.graaljs.External.Main %CMD%
if "%ERRORLEVEL%"=="121" goto runJava
:end
