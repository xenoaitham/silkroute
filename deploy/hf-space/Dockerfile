# SilkRoute live-demo Space: the whole order-mediation demo in one container.
# Kafka (KRaft) + Redis + toxiproxy + the SOAP ERP + the Camel ESB + the demo
# relay, all talking over container-local ports that already match the apps'
# defaults (kafka 39092, redis 16379, toxiproxy 18474, erp 18080, esb 18081).
# The relay serves the playground and the whitelisted API on 7860 (public).
FROM eclipse-temurin:17-jre-noble

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl jq python3 redis-server ca-certificates \
 && rm -rf /var/lib/apt/lists/*
# noble's temurin image ships a default `ubuntu` user at UID 1000 (HF Spaces
# run containers as 1000) - reuse it rather than creating a colliding one

# Kafka 3.8 (KRaft, no zookeeper) - same engine major as the repo's sim
RUN curl -fsSL https://archive.apache.org/dist/kafka/3.8.0/kafka_2.13-3.8.0.tgz -o /tmp/kafka.tgz \
 && mkdir -p /opt/kafka && tar -xzf /tmp/kafka.tgz -C /opt/kafka --strip-components=1 && rm /tmp/kafka.tgz \
 && curl -fsSL https://github.com/Shopify/toxiproxy/releases/download/v2.11.0/toxiproxy-server-linux-amd64 \
      -o /usr/local/bin/toxiproxy-server && chmod +x /usr/local/bin/toxiproxy-server

COPY apps/legacy-erp/target/legacy-erp-1.0.0-SNAPSHOT.jar /opt/app/legacy-erp.jar
COPY apps/esb/target/esb-1.0.0-SNAPSHOT.jar /opt/app/esb.jar
COPY demo/relay.py /opt/app/relay.py
COPY demo/playground.html /opt/app/playground.html
COPY deploy/hf-space/start.sh /opt/app/start.sh

RUN mkdir -p /data/kafka /data/redis \
 && chown -R ubuntu:ubuntu /data /opt/app /opt/kafka

USER ubuntu
ENV KAFKA_HEAP_OPTS="-Xmx512m -Xms256m" \
    ESB_JAVA_OPTS="-Xmx512m" \
    ERP_JAVA_OPTS="-Xmx384m" \
    DEMO_RELAY_PORT=7860 \
    DEMO_RELAY_HOST=0.0.0.0
EXPOSE 7860
CMD ["/opt/app/start.sh"]
