/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ofbiz.efinance.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.Arrays;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * Small persistence-backed guard for idempotent writes and request rate limits.
 * It intentionally keeps the policy at the service layer so the database
 * records remain auditable and work across multiple OFBiz nodes.
 */
public final class EfRequestGuard {

    private static final String MODULE = EfRequestGuard.class.getName();
    private static final String PROPS = "efinance";
    private static final String STATUS_IN_PROGRESS = "EFREQ_IN_PROGRESS";
    private static final String STATUS_COMPLETED = "EFREQ_COMPLETED";
    private static final String STATUS_FAILED = "EFREQ_FAILED";

    public static final String OP_CREATE_DRAFT = "CREATE_DRAFT";
    public static final String OP_SUBMIT_APPLICATION = "SUBMIT_APPLICATION";

    private EfRequestGuard() {
    }

    public enum IdempotencyState {
        NEW,
        REPLAY,
        IN_PROGRESS,
        CONFLICT
    }

    public static final class IdempotencyResult {
        private final IdempotencyState state;
        private final GenericValue record;

        private IdempotencyResult(IdempotencyState state, GenericValue record) {
            this.state = state;
            this.record = record;
        }

        public IdempotencyState getState() {
            return state;
        }

        public GenericValue getRecord() {
            return record;
        }

        public boolean isReplay() {
            return state == IdempotencyState.REPLAY;
        }
    }

    /**
     * Start or resume an idempotent operation. A completed operation returns
     * REPLAY with the original record. A different request hash is CONFLICT.
     */
    public static IdempotencyResult begin(Delegator delegator, String subjectKey, String operation,
            String idempotencyKey, String requestHash) throws GenericEntityException {
        if (UtilValidate.isEmpty(idempotencyKey)) {
            return new IdempotencyResult(IdempotencyState.NEW, null);
        }
        String subject = UtilValidate.isEmpty(subjectKey) ? "anonymous" : subjectKey;
        Timestamp now = UtilDateTime.nowTimestamp();
        GenericValue existing = find(delegator, subject, operation, idempotencyKey);
        if (existing != null && expired(existing, now)) {
            delegator.removeValue(existing);
            existing = null;
        }
        if (existing != null) {
            return classify(existing, requestHash, now);
        }

        GenericValue record = delegator.makeValue("EfIdempotencyRecord");
        record.set("recordId", delegator.getNextSeqId("EfIdempotencyRecord"));
        record.set("subjectKey", subject);
        record.set("operation", operation);
        record.set("idempotencyKey", idempotencyKey);
        record.set("requestHash", requestHash);
        record.set("statusId", STATUS_IN_PROGRESS);
        record.set("createdDate", now);
        record.set("lastModifiedDate", now);
        record.set("expiresDate", expiresAt(now));
        try {
            delegator.create(record);
            return new IdempotencyResult(IdempotencyState.NEW, record);
        } catch (GenericEntityException e) {
            // Another request may have inserted the same key concurrently.
            GenericValue concurrent = find(delegator, subject, operation, idempotencyKey);
            if (concurrent != null) {
                return classify(concurrent, requestHash, now);
            }
            throw e;
        }
    }

    public static void complete(Delegator delegator, IdempotencyResult result, String resultEntityId)
            throws GenericEntityException {
        if (result == null || result.getRecord() == null) {
            return;
        }
        GenericValue record = result.getRecord();
        record.set("statusId", STATUS_COMPLETED);
        record.set("resultEntityId", resultEntityId);
        record.set("lastModifiedDate", UtilDateTime.nowTimestamp());
        delegator.store(record);
    }

    public static void fail(Delegator delegator, IdempotencyResult result) {
        if (result == null || result.getRecord() == null) {
            return;
        }
        try {
            GenericValue record = result.getRecord();
            if (!STATUS_COMPLETED.equals(record.getString("statusId"))) {
                record.set("statusId", STATUS_FAILED);
                record.set("lastModifiedDate", UtilDateTime.nowTimestamp());
                delegator.store(record);
            }
        } catch (GenericEntityException e) {
            Debug.logWarning("Unable to mark idempotent request as failed: " + e.getMessage(), MODULE);
        }
    }

    /**
     * Count one request in the current fixed window. Returns false when the
     * limit has been reached.
     */
    public static boolean checkRateLimit(Delegator delegator, String scope, String subjectKey,
            int limit, int windowSeconds) throws GenericEntityException {
        if (limit <= 0) {
            return true;
        }
        String subject = UtilValidate.isEmpty(subjectKey) ? "anonymous" : subjectKey;
        long windowMillis = Math.max(windowSeconds, 1) * 1000L;
        long nowMillis = System.currentTimeMillis();
        Timestamp windowStart = new Timestamp((nowMillis / windowMillis) * windowMillis);
        GenericValue bucket = findBucket(delegator, scope, subject, windowStart);
        if (bucket == null) {
            GenericValue created = delegator.makeValue("EfRateLimitBucket");
            created.set("bucketId", delegator.getNextSeqId("EfRateLimitBucket"));
            created.set("scope", scope);
            created.set("subjectKey", subject);
            created.set("windowStart", windowStart);
            created.set("requestCount", 1L);
            created.set("lastUpdatedDate", UtilDateTime.nowTimestamp());
            try {
                delegator.create(created);
                return true;
            } catch (GenericEntityException e) {
                bucket = findBucket(delegator, scope, subject, windowStart);
                if (bucket == null) {
                    throw e;
                }
            }
        }
        long count = bucket.getLong("requestCount") == null ? 0L : bucket.getLong("requestCount");
        if (count >= limit) {
            return false;
        }
        bucket.set("requestCount", count + 1L);
        bucket.set("lastUpdatedDate", UtilDateTime.nowTimestamp());
        delegator.store(bucket);
        return true;
    }

    /** Stable request fingerprint used to reject reuse of a key for different content. */
    public static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Arrays.stream(parts).forEach(part -> {
                if (part != null) {
                    digest.update(part.getBytes(StandardCharsets.UTF_8));
                }
                digest.update((byte) 0);
            });
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest.digest()) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static IdempotencyResult classify(GenericValue record, String requestHash, Timestamp now)
            throws GenericEntityException {
        if (!requestHash.equals(record.getString("requestHash"))) {
            return new IdempotencyResult(IdempotencyState.CONFLICT, record);
        }
        String status = record.getString("statusId");
        if (STATUS_COMPLETED.equals(status)) {
            return new IdempotencyResult(IdempotencyState.REPLAY, record);
        }
        if (STATUS_IN_PROGRESS.equals(status)) {
            return new IdempotencyResult(IdempotencyState.IN_PROGRESS, record);
        }
        // FAILED requests may be retried with the same key.
        record.set("statusId", STATUS_IN_PROGRESS);
        record.set("lastModifiedDate", now);
        record.set("expiresDate", expiresAt(now));
        record.getDelegator().store(record);
        return new IdempotencyResult(IdempotencyState.NEW, record);
    }

    private static GenericValue find(Delegator delegator, String subject, String operation,
            String idempotencyKey) throws GenericEntityException {
        return EntityQuery.use(delegator).from("EfIdempotencyRecord")
                .where("subjectKey", subject, "operation", operation, "idempotencyKey", idempotencyKey)
                .cache(false).queryOne();
    }

    private static GenericValue findBucket(Delegator delegator, String scope, String subject,
            Timestamp windowStart) throws GenericEntityException {
        return EntityQuery.use(delegator).from("EfRateLimitBucket")
                .where("scope", scope, "subjectKey", subject, "windowStart", windowStart)
                .cache(false).queryOne();
    }

    private static boolean expired(GenericValue record, Timestamp now) {
        Timestamp expires = record.getTimestamp("expiresDate");
        return expires != null && expires.before(now);
    }

    private static Timestamp expiresAt(Timestamp now) {
        int hours = UtilProperties.getPropertyAsInteger(PROPS, "efinance.idempotency.expire.hours", 24);
        return new Timestamp(now.getTime() + (long) Math.max(hours, 1) * 60L * 60L * 1000L);
    }
}
