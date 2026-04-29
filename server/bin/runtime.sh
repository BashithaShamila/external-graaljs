#!/bin/sh
# ----------------------------------------------------------------------------
#  Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
#
#  WSO2 LLC. licenses this file to you under the Apache License,
#  Version 2.0 (the "License"); you may not use this file except
#  in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
# ----------------------------------------------------------------------------

# ----------------------------------------------------------------------------
# Main script for the WSO2 Identity GraalJS Runtime.
#
# Environment Variable Prerequisites
#
#   GRAALJS_RUNTIME_HOME   Home of the runtime installation. If not set, the
#                          script derives it from its own location.
#
#   JAVA_HOME              Must point at a JDK 11–21 installation.
#
#   JVM_MEM_OPTS           (Optional) Heap sizing for the JVM. Defaults to
#                          "-Xms256m -Xmx512m".
#
#   JAVA_OPTS              (Optional) Additional JVM options.
# ----------------------------------------------------------------------------

# OS-specific support. $var _must_ be set to either true or false.
cygwin=false
darwin=false
os400=false
mingw=false
case "`uname`" in
CYGWIN*) cygwin=true ;;
MINGW*) mingw=true ;;
OS400*) os400=true ;;
Darwin*) darwin=true
        if [ -z "$JAVA_VERSION" ]; then
            JAVA_VERSION="CurrentJDK"
        else
            echo "Using Java version: $JAVA_VERSION"
        fi
        if [ -z "$JAVA_HOME" ]; then
            JAVA_HOME=/System/Library/Frameworks/JavaVM.framework/Versions/${JAVA_VERSION}/Home
        fi
        ;;
esac

# Resolve links — $0 may be a softlink.
PRG="$0"
while [ -h "$PRG" ]; do
  ls=`ls -ld "$PRG"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  if expr "$link" : '.*/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=`dirname "$PRG"`/"$link"
  fi
done

PRGDIR=`dirname "$PRG"`

# Only set GRAALJS_RUNTIME_HOME if not already set.
[ -z "$GRAALJS_RUNTIME_HOME" ] && GRAALJS_RUNTIME_HOME=`cd "$PRGDIR/.." ; pwd`

# For Cygwin, ensure paths are in UNIX format before anything is touched.
if $cygwin; then
  [ -n "$JAVA_HOME" ] && JAVA_HOME=`cygpath --unix "$JAVA_HOME"`
  [ -n "$GRAALJS_RUNTIME_HOME" ] && GRAALJS_RUNTIME_HOME=`cygpath --unix "$GRAALJS_RUNTIME_HOME"`
fi

# For Mingw, ensure paths are in UNIX format before anything is touched.
if $mingw; then
  [ -n "$GRAALJS_RUNTIME_HOME" ] && GRAALJS_RUNTIME_HOME="`(cd "$GRAALJS_RUNTIME_HOME"; pwd)`"
  [ -n "$JAVA_HOME" ] && JAVA_HOME="`(cd "$JAVA_HOME"; pwd)`"
fi

if [ -z "$JAVACMD" ]; then
  if [ -n "$JAVA_HOME" ]; then
    if [ -x "$JAVA_HOME/jre/sh/java" ]; then
      JAVACMD="$JAVA_HOME/jre/sh/java"
    else
      JAVACMD="$JAVA_HOME/bin/java"
    fi
  else
    JAVACMD=java
  fi
fi

if [ ! -x "$JAVACMD" ]; then
  echo "Error: JAVA_HOME is not defined correctly."
  echo " GraalJS Runtime cannot execute $JAVACMD"
  exit 1
fi

if [ -z "$JAVA_HOME" ]; then
  echo "You must set the JAVA_HOME variable before running the GraalJS Runtime."
  exit 1
fi

PID_FILE="$GRAALJS_RUNTIME_HOME/runtime.pid"
if [ -e "$PID_FILE" ]; then
  PID=`cat "$PID_FILE"`
fi

# ----- Process the input command --------------------------------------------
args=""
for c in $*
do
    if [ "$c" = "--debug" ] || [ "$c" = "-debug" ] || [ "$c" = "debug" ]; then
        CMD="--debug"
        continue
    elif [ "$CMD" = "--debug" ]; then
        if [ -z "$PORT" ]; then
            PORT=$c
        fi
    elif [ "$c" = "--stop" ] || [ "$c" = "-stop" ] || [ "$c" = "stop" ]; then
        CMD="stop"
    elif [ "$c" = "--start" ] || [ "$c" = "-start" ] || [ "$c" = "start" ]; then
        CMD="start"
    elif [ "$c" = "--run" ] || [ "$c" = "-run" ] || [ "$c" = "run" ]; then
        CMD="run"
    elif [ "$c" = "--version" ] || [ "$c" = "-version" ] || [ "$c" = "version" ]; then
        CMD="version"
    elif [ "$c" = "--restart" ] || [ "$c" = "-restart" ] || [ "$c" = "restart" ]; then
        CMD="restart"
    else
        args="$args $c"
    fi
done

if [ "$CMD" = "--debug" ]; then
  if [ "$PORT" = "" ]; then
    echo " Please specify the debug port after the --debug option"
    exit 1
  fi
  if [ -n "$JAVA_OPTS" ]; then
    echo "Warning !!!. User specified JAVA_OPTS will be ignored, once you give the --debug option."
  fi
  CMD="RUN"
  JAVA_OPTS="-Xdebug -Xnoagent -Djava.compiler=NONE -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=$PORT"
  echo "Please start the remote debugging client to continue..."
elif [ "$CMD" = "start" ]; then
  if [ -e "$PID_FILE" ]; then
    if ps -p $PID > /dev/null; then
      echo "Process is already running"
      exit 0
    fi
  fi
  export GRAALJS_RUNTIME_HOME="$GRAALJS_RUNTIME_HOME"
  nohup sh "$GRAALJS_RUNTIME_HOME"/bin/runtime.sh run $args > /dev/null 2>&1 &
  exit 0
elif [ "$CMD" = "stop" ]; then
  export GRAALJS_RUNTIME_HOME="$GRAALJS_RUNTIME_HOME"
  if [ ! -e "$PID_FILE" ]; then
    echo "PID file not found at $PID_FILE — runtime is not running."
    exit 1
  fi
  kill -term `cat "$PID_FILE"`
  exit 0
elif [ "$CMD" = "restart" ]; then
  export GRAALJS_RUNTIME_HOME="$GRAALJS_RUNTIME_HOME"
  if [ -e "$PID_FILE" ]; then
    kill -term `cat "$PID_FILE"`
    pid=`cat "$PID_FILE"`
    process_status=0
    while [ "$process_status" -eq "0" ]; do
        sleep 1
        ps -p$pid 2>&1 > /dev/null
        process_status=$?
    done
  fi
  nohup sh "$GRAALJS_RUNTIME_HOME"/bin/runtime.sh run $args > /dev/null 2>&1 &
  exit 0
elif [ "$CMD" = "version" ]; then
  cat "$GRAALJS_RUNTIME_HOME"/bin/version.txt 2>/dev/null
  exit 0
fi

# ---------- Validate JDK version --------------------------------------------
java_version=$("$JAVACMD" -version 2>&1 | awk -F '"' '/version/ {print $2}')
java_version_formatted=$(echo "$java_version" | awk -F. '{printf("%02d%02d",$1,$2);}')
if [ $java_version_formatted -lt 1100 ] || [ $java_version_formatted -gt 2100 ]; then
   echo " Starting GraalJS Runtime (in unsupported JDK)"
   echo " [ERROR] GraalJS Runtime is supported only between JDK 11 and JDK 21"
fi

# ---------- Resolve where the runtime jars live ----------------------------
# Extracted distribution: lib/<runtime>.jar + lib/*.jar dependencies.
# Source tree (after `mvn package`): target/<runtime>.jar + target/lib/*.jar.
# Pick whichever layout exists so both flows work without a separate script.
RUNTIME_LIB_DIR=""
RUNTIME_MAIN_JAR_DIR=""
if [ -d "$GRAALJS_RUNTIME_HOME/lib" ]; then
    for f in "$GRAALJS_RUNTIME_HOME"/lib/*.jar; do
        if [ -f "$f" ]; then
            RUNTIME_LIB_DIR="$GRAALJS_RUNTIME_HOME/lib"
            break
        fi
    done
fi
if [ -z "$RUNTIME_LIB_DIR" ] && [ -d "$GRAALJS_RUNTIME_HOME/target/lib" ]; then
    RUNTIME_LIB_DIR="$GRAALJS_RUNTIME_HOME/target/lib"
    RUNTIME_MAIN_JAR_DIR="$GRAALJS_RUNTIME_HOME/target"
fi
if [ -z "$RUNTIME_LIB_DIR" ]; then
    echo "Error: cannot locate runtime jars."
    echo "  Looked under $GRAALJS_RUNTIME_HOME/lib and $GRAALJS_RUNTIME_HOME/target/lib."
    echo "  Build with 'mvn clean install' or extract the distribution archive first."
    exit 1
fi

# ---------- Build classpath: conf/ first (for simplelogger.properties /
#            log4j2 configuration discovery), then runtime jar(s). -----------
RUNTIME_CLASSPATH="$GRAALJS_RUNTIME_HOME/conf"
if [ -n "$RUNTIME_MAIN_JAR_DIR" ]; then
    for f in "$RUNTIME_MAIN_JAR_DIR"/wso2is-graaljs-runtime-*.jar; do
        # Skip the maven-jar-plugin "original-" backup copy (when shade is in use).
        case "$f" in
            *original-*) continue ;;
        esac
        [ -f "$f" ] && RUNTIME_CLASSPATH="$RUNTIME_CLASSPATH":$f
    done
fi
for f in "$RUNTIME_LIB_DIR"/*.jar; do
    [ -f "$f" ] && RUNTIME_CLASSPATH="$RUNTIME_CLASSPATH":$f
done

# For Cygwin, switch paths to Windows format before running java.
if $cygwin; then
  JAVA_HOME=`cygpath --absolute --windows "$JAVA_HOME"`
  GRAALJS_RUNTIME_HOME=`cygpath --absolute --windows "$GRAALJS_RUNTIME_HOME"`
  RUNTIME_CLASSPATH=`cygpath --path --windows "$RUNTIME_CLASSPATH"`
fi

# ----- Execute the requested command ----------------------------------------
echo JAVA_HOME environment variable is set to $JAVA_HOME
echo GRAALJS_RUNTIME_HOME environment variable is set to "$GRAALJS_RUNTIME_HOME"

cd "$GRAALJS_RUNTIME_HOME"

START_EXIT_STATUS=121
status=$START_EXIT_STATUS

if [ -z "$JVM_MEM_OPTS" ]; then
   JVM_MEM_OPTS="-Xms256m -Xmx512m"
fi
echo "Using Java memory options: $JVM_MEM_OPTS"

JAVA_VER_BASED_OPTS="--add-opens=java.base/java.net=ALL-UNNAMED \
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    --add-opens=java.base/java.io=ALL-UNNAMED \
    --add-opens=java.base/java.util=ALL-UNNAMED \
    --add-opens=java.base/java.security=ALL-UNNAMED \
    --add-opens=java.base/sun.security.util=ALL-UNNAMED"

# Record the PID for stop/restart support.
echo $$ > "$PID_FILE"

while [ "$status" = "$START_EXIT_STATUS" ]
do
    "$JAVACMD" \
    $JVM_MEM_OPTS \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath="$GRAALJS_RUNTIME_HOME/repository/logs/heap-dump.hprof" \
    $JAVA_OPTS \
    -classpath "$RUNTIME_CLASSPATH" \
    $JAVA_VER_BASED_OPTS \
    -Dgraaljs.runtime.home="$GRAALJS_RUNTIME_HOME" \
    -Dconf.location="$GRAALJS_RUNTIME_HOME/conf" \
    -Djava.io.tmpdir="$GRAALJS_RUNTIME_HOME/tmp" \
    -Dfile.encoding=UTF8 \
    -Djava.net.preferIPv4Stack=true \
    -Djava.security.egd=file:/dev/./urandom \
    org.wso2.carbon.identity.graaljs.External.Main $args
    status=$?
done

# Clean up the PID file on a normal exit.
rm -f "$PID_FILE"
