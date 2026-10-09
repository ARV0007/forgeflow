package com.forgeflow.account;

import com.forgeflow.account.dto.UserSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The account module's public face.
 *
 * Other modules look people up through THIS, never through UserRepository.
 * It hands out UserSummary, not the User entity, so nothing outside account
 * can read a password hash or mutate an account by accident - and if account
 * ever becomes its own service, this class is the API it has to keep.
 */
@Service
@Transactional(readOnly = true)
public class UserDirectory {

    private final UserRepository users;

    public UserDirectory(UserRepository users) {
        this.users = users;
    }

    /** Active accounts only. Case-insensitive, and whitespace is not identity. */
    public Optional<UserSummary> findByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return users.findByEmailIgnoreCaseAndDeletedAtIsNull(email.trim()).map(UserDirectory::summarise);
    }

    public Optional<UserSummary> findById(Long id) {
        return users.findById(id).filter(User::isActive).map(UserDirectory::summarise);
    }

    /** One query for a whole member list, not one per member. */
    public Map<Long, UserSummary> findByIds(Collection<Long> ids) {
        Map<Long, UserSummary> out = new LinkedHashMap<>();
        for (User u : users.findAllById(ids)) {
            out.put(u.getId(), summarise(u));
        }
        return out;
    }

    static UserSummary summarise(User u) {
        return new UserSummary(u.getId(), u.getEmail(), u.getName(), u.getAvatarUrl());
    }
}
