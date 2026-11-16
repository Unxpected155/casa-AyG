package com.casaayg.shared.security;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.casaayg.audit.AuditService;

/** Repositorio JDBC de passkeys de Spring + auditoría de cada passkey nueva o borrada. */
public class AuditingUserCredentialRepository implements UserCredentialRepository {

    private final UserCredentialRepository delegate;
    private final AuditService audit;

    public AuditingUserCredentialRepository(UserCredentialRepository delegate, AuditService audit) {
        this.delegate = delegate;
        this.audit = audit;
    }

    @Override
    public void save(CredentialRecord record) {
        boolean isNew = delegate.findByCredentialId(record.getCredentialId()) == null;
        delegate.save(record);
        if (isNew) {
            UUID user = CurrentUser.fromUserHandle(record.getUserEntityUserId().getBytes());
            audit.record(user, "PASSKEY_REGISTRADA", Map.of("etiqueta", record.getLabel()), currentRequest());
        }
    }

    @Override
    public void delete(Bytes credentialId) {
        delegate.delete(credentialId);
    }

    @Override
    public CredentialRecord findByCredentialId(Bytes credentialId) {
        return delegate.findByCredentialId(credentialId);
    }

    @Override
    public List<CredentialRecord> findByUserId(Bytes userId) {
        return delegate.findByUserId(userId);
    }

    private static jakarta.servlet.http.HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
            ? attrs.getRequest() : null;
    }
}
