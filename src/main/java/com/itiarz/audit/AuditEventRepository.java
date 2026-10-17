package com.itiarz.audit;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO audit_events (id, tenant_id, actor_user_id, action, target_type, target_id,
                                      details, ip, trace_id, occurred_at)
            VALUES (:id, :tenantId, :actorUserId, :action, :targetType, :targetId,
                    CAST(:details AS jsonb), CAST(:ip AS inet), :traceId, now())
            """, nativeQuery = true)
    int insert(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("actorUserId") UUID actorUserId,
               @Param("action") String action, @Param("targetType") String targetType,
               @Param("targetId") String targetId, @Param("details") String detailsJson,
               @Param("ip") String ip, @Param("traceId") String traceId);

    /** Row for the reader: event plus the actor e-mail (null for system/platform events). */
    record Row(AuditEvent event, String actorEmail) { }

    /** Tenant scope comes from RLS (app.tenant_id), not from the query. */
    @Query(value = """
            select new com.itiarz.audit.AuditEventRepository$Row(e, u.email)
            from AuditEvent e left join com.itiarz.membership.AppUser u on u.id = e.actorUserId
            order by e.occurredAt desc, e.id desc
            """,
            countQuery = "select count(e) from AuditEvent e")
    Page<Row> findPage(Pageable pageable);
}
