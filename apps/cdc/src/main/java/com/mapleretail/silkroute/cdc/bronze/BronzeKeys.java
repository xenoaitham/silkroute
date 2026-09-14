package com.mapleretail.silkroute.cdc.bronze;

import com.mapleretail.silkroute.cdc.common.Env;

/**
 * Bronze object keys, EXACTLY per ADR-0006 decision 3:
 *
 *   region=&lt;REGION&gt;/table=&lt;table&gt;/dt=&lt;YYYYMMDD&gt;/&lt;topic&gt;-&lt;partition&gt;-&lt;offset&gt;-&lt;uuid4&gt;.jsonl
 *
 * Uniqueness of (topic, partition, offset, uuid4) makes keys unique per landing
 * attempt: a re-run writes NEW keys - bronze is write-once by construction.
 * There is deliberately NO delete/copy/overwrite helper anywhere in this module.
 */
public final class BronzeKeys {

    private BronzeKeys() {
    }

    public static String objectKey(String region, String table, String dt, String topic, int partition, long offset, String uuid4) {
        return "region=" + region
                + "/table=" + table
                + "/dt=" + dt
                + "/" + topic + "-" + partition + "-" + offset + "-" + uuid4 + ".jsonl";
    }

    public static String dtFolder(long sourceTsMs) {
        return Env.dtUtc(sourceTsMs);
    }
}
