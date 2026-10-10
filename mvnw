#!/bin/sh
# ----------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# ----------------------------------------------------------------------------

# ----------------------------------------------------------------------------
# Apache Maven Wrapper startup batch script, version 3.3.2
#
# Required ENV vars:
# ------------------
#   JAVA_HOME - location of a JDK home dir
#
# Optional ENV vars
# -----------------
#   MAVEN_OPTS - parameters passed to the Java VM when running Maven
#     e.g. to debug Maven itself, use
#       set MAVEN_OPTS=-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=8000
#   MAVEN_SKIP_RC - flag to disable loading of mavenrc files
# ----------------------------------------------------------------------------

if [ -z "$MAVEN_SKIP_RC" ] ; then

  if [ -f /usr/local/etc/mavenrc ] ; then
    . /usr/local/etc/mavenrc
  fi

  if [ -f /etc/mavenrc ] ; then
    . /etc/mavenrc
  fi

  if [ -f "$HOME/.mavenrc" ] ; then
    . "$HOME/.mavenrc"
  fi

fi

# OS specific support.  $var _must_ be set to either true or false.
cygwin=false;
darwin=false;
mingw=false
case "`uname`" in
  CYGWIN*) cygwin=true ;;
  MINGW*) mingw=true;;
  Darwin*) darwin=true
    # Use /usr/libexec/java_home if available, otherwise fall through
    # We use darwin* rather than darwin-* to capture Darwin 7.x
    if [ -z "$JAVA_HOME" ]; then
      if [ -x /usr/libexec/java_home ]; then
        JAVA_HOME=`/usr/libexec/java_home`; export JAVA_HOME
      fi
    fi
    ;;
esac

if [ -z "$JAVA_HOME" ] ; then
  if [ -r /etc/gentoo-release ] ; then
    JAVA_HOME=`java-config --jre-home`
  fi
fi

# For Cygwin, ensure paths are in UNIX format before anything is touched
if $cygwin ; then
  [ -n "$JAVA_HOME" ] &&
    JAVA_HOME=`cygpath --unix "$JAVA_HOME"`
  [ -n "$CLASSPATH" ] &&
    CLASSPATH=`cygpath --path --unix "$CLASSPATH"`
fi

# For Mingw, ensure paths are in UNIX format before anything is touched
if $mingw ; then
  [ -n "$JAVA_HOME" ] &&
    JAVA_HOME="`(cd "$JAVA_HOME"; pwd)`"
fi

if [ -z "$JAVA_HOME" ]; then
  javaExecutable="`which javaw`"
  if [ -n "$javaExecutable" ] && ! [ "`ls -la $javaExecutable | grep \"-> /\"`" ]; then
    # Resolve the symlinks
    resolvedExecutable="`readlink -f $javaExecutable`"
    # Do a multiple-level absolute path resolution to see if we can reach back to
    # the bin directory
    javaExecutable="`dirname $resolvedExecutable`"
    javaExecutable="`cd $javaExecutable; pwd -P`"
    javaHome="`dirname $javaExecutable`"
    javaHome="`cd $javaHome; pwd -P`"
    JAVA_HOME="$javaHome"
    export JAVA_HOME
  fi

  if [ -z "$JAVA_HOME" ]; then
    # Try to find via java itself
    javaExecutable="`which java`"
    if [ -n "$javaExecutable" ]; then
      resolvedExecutable="`readlink -f $javaExecutable`"
      javaExecutable="`dirname $resolvedExecutable`"
      javaExecutable="`cd $javaExecutable; pwd -P`"
      javaHome="`dirname $javaExecutable`"
      javaHome="`cd $javaHome; pwd -P`"
      JAVA_HOME="$javaHome"
      export JAVA_HOME
    fi
  fi
fi

if [ -z "$JAVACMD" ] ; then
  if [ -n "$JAVA_HOME"  ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
      # IBM's JDK on AIX uses strange locations for the executables
      JAVACMD="$JAVA_HOME/jre/sh/java"
    else
      JAVACMD="$JAVA_HOME/bin/java"
    fi
  else
    JAVACMD="`\\unset -f command; \\command -v java`"
  fi
fi

if [ ! -x "$JAVACMD" ] ; then
  echo "Error: JAVA_HOME is not defined correctly." >&2
  echo "  We cannot execute $JAVACMD" >&2
  exit 1
fi

if [ -z "$JAVA_HOME" ] ; then
  echo "Warning: JAVA_HOME environment variable is not set."
fi

# traverses directory structure from process work directory to filesystem root,
# trying to find a'.mvn' directory and returns the first found
find_maven_basedir() {

  if [ -z "$1" ]
  then
    echo "Path not specified to find_maven_basedir"
    return 1
  fi

  basedir="$1"
  wdir="$1"
  while [ "$wdir" != '/' ] ; do
    if [ -d "$wdir"/.mvn ] ; then
      basedir=$wdir
      break
    fi
    # workaround for JBEAP-8937 (on Solaris 10/Sparc)
    if [ -d "${wdir}" ]; then
      wdir=`cd "$wdir/.."; pwd`
    fi
    # end JBEAP-8937
  done
  printf '%s' "$(cd "$basedir"; pwd -P)"
}

# concatenates all lines of a file
concat_lines() {
  if [ -f "$1" ]; then
    echo "$(tr -s '\n' ' ' < "$1")"
  fi
}

log() {
  if [ "$MVNW_VERBOSE" = true ]; then
    printf '%s\n' "$1"
  fi
}

BASE_DIR=$(find_maven_basedir "$(dirname $0)")
if [ -z "$BASE_DIR" ]; then
  exit 1;
fi

MAVEN_PROJECTBASEDIR=${MAVEN_BASEDIR:-"$BASE_DIR"}; export MAVEN_PROJECTBASEDIR
log "$MAVEN_PROJECTBASEDIR"

##########################################################################################
# Extension to allow automatically downloading the maven-wrapper.jar from Maven-central
# This allows using the maven wrapper in projects that prohibit checking in binary data.
##########################################################################################
if [ -r "$BASE_DIR/.mvn/wrapper/maven-wrapper.jar" ]; then
    log "Found $BASE_DIR/.mvn/wrapper/maven-wrapper.jar"
else
    log "Couldn't find $BASE_DIR/.mvn/wrapper/maven-wrapper.jar, downloading it ..."

    if [ -n "$MVNW_REPOURL" ]; then
      wrapperUrl="$MVNW_REPOURL/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar"
    else
      wrapperUrl="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar"
    fi
    while IFS="=" read key value; do
      case "$key" in (wrapperUrl) wrapperUrl="$value"; break ;;
      esac
    done < "$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"
    log "Downloading from: $wrapperUrl"

    wrapperJarPath="$BASE_DIR/.mvn/wrapper/maven-wrapper.jar"
    if $cygwin; then
      wrapperJarPath=`cygpath --path --windows "$wrapperJarPath"`
    fi

    if command -v wget > /dev/null; then
        log "Found wget ... using wget"
        [ "$MVNW_VERBOSE" = true ] && QUIET="" || QUIET="--quiet"
        if [ -z "$MVNW_USERNAME" ] || [ -z "$MVNW_PASSWORD" ]; then
            wget $QUIET "$wrapperUrl" -O "$wrapperJarPath" || rm -f "$wrapperJarPath"
        else
            wget $QUIET --http-user=$MVNW_USERNAME --http-password=$MVNW_PASSWORD "$wrapperUrl" -O "$wrapperJarPath" || rm -f "$wrapperJarPath"
        fi
    elif command -v curl > /dev/null; then
        log "Found curl... using curl"
        [ "$MVNW_VERBOSE" = true ] && QUIET="" || QUIET="--silent"
        if [ -z "$MVNW_USERNAME" ] || [ -z "$MVNW_PASSWORD" ]; then
            curl $QUIET -o "$wrapperJarPath" "$wrapperUrl" -f || rm -f "$wrapperJarPath"
        else
            curl $QUIET --user $MVNW_USERNAME:$MVNW_PASSWORD -o "$wrapperJarPath" "$wrapperUrl" -f || rm -f "$wrapperJarPath"
        fi
    else
        echo "No download tool found. Please download $wrapperUrl manually to $wrapperJarPath"
        exit 1
    fi
fi
##########################################################################################
# End of extension
##########################################################################################

export MAVEN_OPTS
export MAVEN_DEBUG_OPTS

WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain

exec "$JAVACMD" \
  $MAVEN_OPTS \
  $MAVEN_DEBUG_OPTS \
  -classpath "$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar" \
  "-Dmaven.home=${M2_HOME}" \
  "-Dmaven.multiModuleProjectDirectory=${MAVEN_PROJECTBASEDIR}" \
  ${WRAPPER_LAUNCHER} "$@"
