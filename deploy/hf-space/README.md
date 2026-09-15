---
title: SilkRoute live demo
emoji: 🛣️
colorFrom: gray
colorTo: blue
sdk: docker
app_port: 7860
pinned: false
short_description: Drive a real running order-mediation platform - place orders, break the ERP, watch it cope
---

# SilkRoute live demo

A self-contained, always-on instance of the SilkRoute order-mediation demo:
Kafka, Redis, a simulated legacy SOAP ERP, the Apache Camel ESB (saga +
circuit breaker + retry + idempotency), the fault-injection proxy, and a
small safety relay - all in one container, serving the interactive
playground on port 7860.

The scenario company (Maple Retail Group) is fictional. All credentials are
documented sim dummies. This is a self-directed reference implementation -
see the [repository](https://github.com/xenoaitham/silkroute) for the full
evidence-backed build.
