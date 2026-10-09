package com.forgeflow.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Spec: PLAN. A named set of limits. Rows are seeded by V5 and read-only to the app. */
@Entity
@Table(name = "plans")
public class Plan {

    public static final String FREE = "FREE";
    public static final String PRO = "PRO";
    public static final String INTERNAL = "INTERNAL";

    @Id
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "price_cents", nullable = false)
    private Integer priceCents;

    @Column(nullable = false)
    private String currency;

    @Column(name = "max_projects", nullable = false)
    private Integer maxProjects;

    @Column(name = "max_tokens_per_day", nullable = false)
    private Integer maxTokensPerDay;

    @Column(name = "max_previews", nullable = false)
    private Integer maxPreviews;

    @Column(name = "unlimited_ai", nullable = false)
    private Boolean unlimitedAi;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String features;

    @Column(nullable = false)
    private Boolean purchasable;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The limit for one quota, or -1 for none. */
    public long limitFor(Quota quota) {
        return switch (quota) {
            case PROJECTS -> maxProjects;
            case PREVIEWS -> maxPreviews;
            case AI_TOKENS_PER_DAY -> Boolean.TRUE.equals(unlimitedAi) ? -1 : maxTokensPerDay;
        };
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public Integer getPriceCents() { return priceCents; }
    public String getCurrency() { return currency; }
    public Integer getMaxProjects() { return maxProjects; }
    public Integer getMaxTokensPerDay() { return maxTokensPerDay; }
    public Integer getMaxPreviews() { return maxPreviews; }
    public Boolean getUnlimitedAi() { return unlimitedAi; }
    public String getFeatures() { return features; }
    public Boolean getPurchasable() { return purchasable; }
}
