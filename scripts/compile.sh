#!/bin/bash
# 编译（JDK17，offline）
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
cd /mnt/d/Documents/workbench/bulkhaul/bulkhaul-server
mvn -q compile -o 2>&1 | tail -50
echo "EXIT=${PIPESTATUS[0]}"
