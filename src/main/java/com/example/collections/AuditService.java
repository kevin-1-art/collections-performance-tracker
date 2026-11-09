package com.example.collections;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuditService {
    private final AuditRepository repository;
    AuditService(AuditRepository repository) { this.repository=repository; }
    @Transactional(propagation=Propagation.MANDATORY)
    void record(Long actorId, String action, String type, String id, String details) {
        AuditEvent e=new AuditEvent(); e.actorId=actorId; e.action=action; e.entityType=type; e.entityId=id; e.details=details;
        repository.save(e);
    }
}
