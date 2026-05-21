package ru.bmstu.dzhioev.urlshortener.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.bmstu.dzhioev.urlshortener.entity.Link;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface LinkRepository extends JpaRepository<Link, Long> {

    Optional<Link> findByShortCode(String shortCode);

    Optional<Link> findFirstByOriginalUrlAndExpiresAtAfterOrderByCreatedAtDesc(String originalUrl, Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "DELETE FROM links WHERE id IN (SELECT id FROM links WHERE expires_at < :now LIMIT :limit)",
            nativeQuery = true
    )
    int deleteExpiredBatch(@Param("now") Instant now, @Param("limit") int limit);
}
