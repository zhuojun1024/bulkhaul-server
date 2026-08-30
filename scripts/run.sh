#!/bin/bash
# 启动服务（JDK17，offline，端口 8081）。用 Hermes terminal background 模式保活（nohup 在 WSL 会话退出会杀进程）。
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
cd /mnt/d/Documents/workbench/bulkhaul/bulkhaul-server
exec mvn -q -o spring-boot:run 2>&1 | grep -vE "Preparing:|Parameters:|Columns:|Row:|Total:|Closing|Creating|JDBC Connection|SqlSession"
