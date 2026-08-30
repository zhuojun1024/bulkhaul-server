#!/bin/bash
for i in $(seq 1 50); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:8081/api/snapshot)
  if [ "$code" = '401' ] || [ "$code" = '200' ]; then
    echo "READY after ${i}x3s (HTTP $code)"
    exit 0
  fi
  sleep 3
done
echo "NOT READY after 150s"
tail -20 /tmp/bulkhaul-server.log | grep -vE 'Preparing|Parameters|Columns|Row|Total|Closing|Creating|JDBC|SqlSession'
exit 1
