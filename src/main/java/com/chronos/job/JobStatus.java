package com.chronos.job;

public enum JobStatus {
    /** Cron occurrences are being enqueued and dispatched. */
    ENABLED,
    /** Schedule is suspended; no new occurrences are enqueued. */
    PAUSED,
    /** Soft delete. Never runs again and is hidden from default listings. */
    ARCHIVED
}
