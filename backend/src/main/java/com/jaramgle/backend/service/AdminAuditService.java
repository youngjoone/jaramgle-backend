package com.jaramgle.backend.service;

import com.jaramgle.backend.entity.AdminAuditLog;
import com.jaramgle.backend.entity.User;
import com.jaramgle.backend.repository.AdminAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private final AdminAuditLogRepository adminAuditLogRepository;

    public void record(User admin, String action, String targetType, String targetId, String detail) {
        AdminAuditLog log = new AdminAuditLog();
        log.setAdminUser(admin);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetail(detail);
        adminAuditLogRepository.save(log);
    }
}
