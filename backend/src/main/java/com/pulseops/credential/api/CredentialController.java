package com.pulseops.credential.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.Permissions;
import com.pulseops.common.tenancy.RequiresPermission;
import com.pulseops.credential.service.CredentialService;
import com.pulseops.credential.service.CredentialView;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Integration credential management. Responses carrying a key are marked no-store. */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
public class CredentialController {

    private final CredentialService credentialService;

    public CredentialController(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @GetMapping("/projects/{projectId}/credentials")
    @RequiresPermission(Permissions.CREDENTIAL_MANAGE)
    public List<CredentialView> list(@PathVariable UUID orgId, @PathVariable UUID projectId, OrgAccess access) {
        return credentialService.listForProject(access, projectId);
    }

    @PostMapping("/projects/{projectId}/credentials")
    @RequiresPermission(Permissions.CREDENTIAL_MANAGE)
    public ResponseEntity<CredentialView.Issued> create(@PathVariable UUID orgId, @PathVariable UUID projectId,
                                                        OrgAccess access,
                                                        @Valid @RequestBody CreateCredentialRequest body) {
        CredentialView.Issued issued = credentialService.create(access, projectId, body.serviceId(), body.name(),
                body.expiresInDays());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(issued);
    }

    @PostMapping("/credentials/{credentialId}/revoke")
    @RequiresPermission(Permissions.CREDENTIAL_MANAGE)
    public CredentialView revoke(@PathVariable UUID orgId, @PathVariable UUID credentialId, OrgAccess access) {
        return credentialService.revoke(access, credentialId);
    }

    @PostMapping("/credentials/{credentialId}/rotate")
    @RequiresPermission(Permissions.CREDENTIAL_MANAGE)
    public ResponseEntity<CredentialView.Issued> rotate(@PathVariable UUID orgId, @PathVariable UUID credentialId,
                                                        OrgAccess access,
                                                        @Valid @RequestBody RotateCredentialRequest body) {
        CredentialView.Issued issued = credentialService.rotate(access, credentialId,
                body.graceHours() == null ? 0 : body.graceHours());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(issued);
    }

    public record CreateCredentialRequest(
            @NotBlank @Size(min = 2, max = 120) @Pattern(regexp = "^[^\\p{Cntrl}]+$") String name,
            UUID serviceId,
            @Min(1) @Max(365) Integer expiresInDays) {
    }

    public record RotateCredentialRequest(@Min(0) @Max(72) Integer graceHours) {
    }
}
