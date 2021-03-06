#!/usr/bin/env bash

PRG="$0"
PRGDIR=`dirname "$PRG"`
APP_HOME=`cd "$PRGDIR/.." >/dev/null; pwd`
WORK_DIRECTORY="$APP_HOME/results/logs"

APP_PID=$RANDOM
TODAY=`date +%Y-%m-%d.%H-%M-%S`

if [ ! -d "$WORK_DIRECTORY" ]; then
  mkdir -p "$WORK_DIRECTORY"
fi

CLASS_PATH="\
$APP_HOME/conf:\
$APP_HOME/benchmark.geode-1.0-SNAPSHOT.jar:\
$APP_HOME/lib/*"

MEM_OPTS="-Xms2g -Xmx2g -XX:+HeapDumpOnOutOfMemoryError"
GC_OPTS="\
-XX:+UseG1GC \
-XX:+PrintGCDetails \
-XX:+PrintGCDateStamps \
-XX:+PrintGCTimeStamps \
-Xloggc:$WORK_DIRECTORY/geode-gc.$TODAY.$APP_PID.log"

#-XX:+PrintCompilation -verbose:gc \

JAVA_OPTS="-server -showversion \
-DgemfirePropertyFile=$APP_HOME/conf/geode-client.properties \
-Dgemfire.cache-xml-file=$APP_HOME/conf/geode-client.xml \
-Dgemfire.name=client-geode \
-Dgemfire.log-file=$WORK_DIRECTORY/client-geode.$TODAY.$APP_PID.log \
-Dgemfire.statistic-archive-file=$WORK_DIRECTORY/client-geode.$TODAY.$APP_PID.gfs \
-Dgemfire.statistic-sampling-enabled=true \
-DLOCATOR_HOST=localhost \
-DLOCATOR_PORT=10680 \
-Dlog4j.configurationFile=$APP_HOME/conf/log4j2.xml \
-Dbenchmark.record.count=100000 \
-Dbenchmark.batch.size=5000 \
-Dbenchmark.range.percent=0.05 \
$MEM_OPTS $GC_OPTS"

JMH_OPTS="-wi 1 -t 1 -i 1 -f 1 -gc true  -rf json -rff $APP_HOME/results/geode.$TODAY.$APP_PID.json -o $APP_HOME/results/geode.$TODAY.$APP_PID.txt -jvmArgsAppend -ea"

COMMAND_LINE="java $JAVA_OPTS -cp $CLASS_PATH org.openjdk.jmh.Main GeodeUseCasesBenchmark $JMH_OPTS"

echo ${COMMAND_LINE}
${COMMAND_LINE}
