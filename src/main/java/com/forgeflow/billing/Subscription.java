package com.forgeflow.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/** Spec: SUBSCRIPTION. Which plan a user is on, and what the payment provider says about it. */
@Entity
@Table(name = "subscriptions")
public class Subscription {

    public static final String ACTIVE = "ACTIVE";
    public static final String PAST_DUE = "PAST_DUE";
    public static final String INCOMPLETE = "INCOMPLETE";
    public static final String CANCELED = "CANCELED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(nullable = false)
    private String status;

    /** stripe | fake | internal */
    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_subscription_id", unique = true)
    private String providerSubscriptionId;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "cancel_at_period_end", nullable = false)
    private Boolean cancelAtPeriodEnd = false;

    @Column(name = "provider_event_at")
    private Instant providerEventAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** ACTIVE, or PAST_DUE while the provider retries the card - a failed charge is not an eviction. */
    public boolean isLive() {
        return ACTIVE.equals(status) || PAST_DUE.equals(status);
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderSubscriptionId() { return providerSubscriptionId; }
    public void setProviderSubscriptionId(String id) { this.providerSubscriptionId = id; }
    public Instant getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(Instant currentPeriodEnd) { this.currentPeriodEnd = currentPeriodEnd; }
    public Boolean getCancelAtPeriodEnd() { return cancelAtPeriodEnd; }
    public void setCancelAtPeriodEnd(Boolean cancelAtPeriodEnd) { this.cancelAtPeriodEnd = cancelAtPeriodEnd; }
    public Instant getProviderEventAt() { return providerEventAt; }
    public void setProviderEventAt(Instant providerEventAt) { this.providerEventAt = providerEventAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
