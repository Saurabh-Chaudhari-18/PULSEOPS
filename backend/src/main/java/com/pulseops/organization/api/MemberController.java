package com.pulseops.organization.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.Permissions;
import com.pulseops.common.tenancy.RequiresPermission;
import com.pulseops.common.web.PageQuery;
import com.pulseops.common.web.PageResponse;
import com.pulseops.organization.service.MemberService;
import com.pulseops.organization.service.OrganizationViews.MemberView;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    /** Any member can see the team. */
    @GetMapping("/members")
    public PageResponse<MemberView> list(@PathVariable UUID orgId, OrgAccess access,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return memberService.list(access, PageQuery.of(page, size));
    }

    @PostMapping("/members")
    @RequiresPermission(Permissions.ORG_MEMBERS_MANAGE)
    public ResponseEntity<MemberCreated> add(@PathVariable UUID orgId, OrgAccess access,
                                             @Valid @RequestBody AddMemberRequest body) {
        UUID memberId = memberService.add(access, body.email(), body.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(new MemberCreated(memberId));
    }

    @PatchMapping("/members/{memberId}")
    @RequiresPermission(Permissions.ORG_MEMBERS_MANAGE)
    public ResponseEntity<Void> changeRole(@PathVariable UUID orgId, @PathVariable UUID memberId,
                                           OrgAccess access, @Valid @RequestBody ChangeRoleRequest body) {
        memberService.changeRole(access, memberId, body.role());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/members/{memberId}")
    @RequiresPermission(Permissions.ORG_MEMBERS_MANAGE)
    public ResponseEntity<Void> remove(@PathVariable UUID orgId, @PathVariable UUID memberId, OrgAccess access) {
        memberService.remove(access, memberId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/leave")
    public ResponseEntity<Void> leave(@PathVariable UUID orgId, OrgAccess access) {
        memberService.leave(access);
        return ResponseEntity.noContent().build();
    }

    public record AddMemberRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 30) String role) {
    }

    public record ChangeRoleRequest(@NotBlank @Size(max = 30) String role) {
    }

    public record MemberCreated(UUID memberId) {
    }
}
