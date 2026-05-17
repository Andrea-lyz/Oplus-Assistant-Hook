#!/bin/sh

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit
APP_BASE_NAME=${0##*/}
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

exec java $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
  -Dorg.gradle.appname="$APP_BASE_NAME" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain "$@"
