package com.chronos.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.chronos.auth.User;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    // LAZY so listing jobs does not fan out into a user query per row. Ownership checks only
    // ever need owner.getId(), which a lazy proxy answers without hitting the database.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "target_url", nullable = false, length = 2048)
    private String targetUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "http_method", nullable = false, length = 10)
    private HttpMethodType httpMethod;

    // @JdbcTypeCode(JSON) tells Hibernate to bind these through Postgres' jsonb type rather
    // than as plain text. Headers get a typed Map (Hibernate serialises it with Jackson);
    // the payload stays a raw String because it is the caller's opaque request body and we
    // must forward it byte-for-byte rather than round-trip it through our own object model.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers_json")
    private Map<String, String> headers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json")
    private String payload;

    @Column(name = "cron_expr", nullable = false, length = 200)
    private String cronExpr;

    // IANA zone id (e.g. "America/New_York"). Cron is evaluated in this zone so that
    // "every day at 02:00" stays at 02:00 local across daylight-saving transitions.
    @Column(nullable = false, length = 64)
    private String timezone;

    /** Next cron occurrence, as a UTC instant. Null once the job stops producing occurrences. */
    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobStatus status;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "initial_backoff_sec", nullable = false)
    private int initialBackoffSec;

    // BigDecimal, not double: the column is NUMERIC(5,2) and Hibernate's schema validation
    // rejects a double mapping against it.
    @Column(name = "backoff_multiplier", nullable = false, precision = 5, scale = 2)
    private BigDecimal backoffMultiplier;

    @Column(name = "timeout_sec", nullable = false)
    private int timeoutSec;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
