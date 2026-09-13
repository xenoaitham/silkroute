package com.mapleretail.silkroute.oms.event;

/** Kafka record metadata kept on the order row for lineage (ADR-0006 decision 2). */
public record RecordMeta(String topic, int partition, long offset, long timestamp) {
}
