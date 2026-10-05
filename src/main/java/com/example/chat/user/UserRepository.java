package com.example.chat.user;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    /**
     * Used by the "start a new chat" search box. Spring Data escapes LIKE wildcards in
     * "Containing" arguments, so a search for "_" doesn't match every user.
     */
    List<User> findTop20ByUsernameContainingIgnoreCaseAndIdNotOrderByUsername(String query, Long excludeId);

    /**
     * Returns the user with this name, creating it first if needed. If two tabs log in with the
     * same new name at once, one insert hits the UNIQUE constraint and that caller just re-reads
     * the winner's row. This works because the method itself is not transactional: the failed
     * insert rolls back on its own and the re-read runs in a fresh transaction.
     */
    default User findOrCreate(String username) {
        return findByUsername(username).orElseGet(() -> {
            try {
                return saveAndFlush(new User(username));
            } catch (DataIntegrityViolationException e) {
                return findByUsername(username).orElseThrow();
            }
        });
    }
}
