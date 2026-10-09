package com.forgeflow.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /** At most one live row per user - a partial unique index enforces it. */
    Optional<Subscription> findFirstByUserIdAndStatusIn(Long userId, Collection<String> statuses);

    List<Subscription> findByUserIdAndStatusIn(Long userId, Collection<String> statuses);

    Optional<Subscription> findByProviderSubscriptionId(String providerSubscriptionId);
}
