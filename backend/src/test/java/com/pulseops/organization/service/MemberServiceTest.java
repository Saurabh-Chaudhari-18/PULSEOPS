package com.pulseops.organization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.pulseops.audit.service.AuditService;
import com.pulseops.common.error.ApiException;
import com.pulseops.common.tenancy.OrgAccess;
import com.pulseops.common.tenancy.Permissions;
import com.pulseops.organization.domain.MemberStatus;
import com.pulseops.organization.domain.OrganizationMember;
import com.pulseops.organization.repository.OrganizationMemberRepository;
import com.pulseops.rbac.domain.Role;
import com.pulseops.rbac.repository.RoleRepository;
import com.pulseops.testsupport.MutableClock;
import com.pulseops.user.domain.User;
import com.pulseops.user.domain.UserStatus;
import com.pulseops.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MemberServiceTest {

    private final OrganizationMemberRepository members = mock(OrganizationMemberRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final MutableClock clock = MutableClock.startingAt("2026-03-01T10:00:00Z");
    private final MemberService service =
            new MemberService(members, roles, users, audit, mock(JdbcTemplate.class), clock);

    private final UUID orgId = UUID.randomUUID();
    private final Role ownerRole = role("OWNER", true);
    private final Role adminRole = role("ADMIN", false);

    @BeforeEach
    void stubRoles() {
        lenient().when(roles.findSystemRole("OWNER")).thenReturn(Optional.of(ownerRole));
        lenient().when(roles.findSystemRole("ADMIN")).thenReturn(Optional.of(adminRole));
        lenient().when(roles.findById(ownerRole.getId())).thenReturn(Optional.of(ownerRole));
        lenient().when(roles.findById(adminRole.getId())).thenReturn(Optional.of(adminRole));
    }

    @Test
    void adminCannotGrantOwnerRole() {
        assertThatThrownBy(() -> service.add(access(adminRole), "bob@acme.test", "owner"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("OWNER_REQUIRED");
        verifyNoInteractions(users);
    }

    @Test
    void unknownRoleIsRejected() {
        when(roles.findSystemRole("GOD")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.add(access(ownerRole), "bob@acme.test", "god"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("UNKNOWN_ROLE");
    }

    @Test
    void addingAnActiveMemberAgainConflicts() {
        User bob = new User("bob@acme.test", "h", "Bob", UserStatus.ACTIVE);
        when(users.findByNormalizedEmail("bob@acme.test")).thenReturn(Optional.of(bob));
        when(members.findByUser(orgId, bob.getId())).thenReturn(Optional.of(
                OrganizationMember.active(orgId, bob.getId(), adminRole.getId(), null, clock.instant())));

        assertThatThrownBy(() -> service.add(access(ownerRole), "Bob@Acme.test", "admin"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("ALREADY_MEMBER");
    }

    @Test
    void removedMemberIsReactivatedWithTheNewRole() {
        User bob = new User("bob@acme.test", "h", "Bob", UserStatus.ACTIVE);
        OrganizationMember removed = OrganizationMember.active(orgId, bob.getId(), ownerRole.getId(), null,
                clock.instant());
        removed.remove();
        when(users.findByNormalizedEmail("bob@acme.test")).thenReturn(Optional.of(bob));
        when(members.findByUser(orgId, bob.getId())).thenReturn(Optional.of(removed));

        UUID memberId = service.add(access(ownerRole), "bob@acme.test", "admin");

        assertThat(memberId).isEqualTo(removed.getId());
        assertThat(removed.isActive()).isTrue();
        assertThat(removed.getRoleId()).isEqualTo(adminRole.getId());
        verify(members, never()).save(any());
    }

    @Test
    void lastOwnerCannotBeDemoted() {
        OrganizationMember onlyOwner = member(ownerRole);
        when(members.findInOrganization(orgId, onlyOwner.getId())).thenReturn(Optional.of(onlyOwner));
        when(members.lockByRole(orgId, ownerRole.getId(), MemberStatus.ACTIVE)).thenReturn(List.of(onlyOwner));

        assertThatThrownBy(() -> service.changeRole(access(ownerRole), onlyOwner.getId(), "admin"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("LAST_OWNER");
        assertThat(onlyOwner.getRoleId()).isEqualTo(ownerRole.getId());
    }

    @Test
    void ownerCanBeDemotedWhenAnotherOwnerRemains() {
        OrganizationMember target = member(ownerRole);
        OrganizationMember otherOwner = member(ownerRole);
        when(members.findInOrganization(orgId, target.getId())).thenReturn(Optional.of(target));
        when(members.lockByRole(orgId, ownerRole.getId(), MemberStatus.ACTIVE))
                .thenReturn(List.of(target, otherOwner));

        service.changeRole(access(ownerRole), target.getId(), "admin");

        assertThat(target.getRoleId()).isEqualTo(adminRole.getId());
        verify(audit).record(any(), eq("member.role_changed"), eq("organization_member"), eq(target.getId()), anyMap());
    }

    @Test
    void adminCannotRemoveAnOwner() {
        OrganizationMember owner = member(ownerRole);
        when(members.findInOrganization(orgId, owner.getId())).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.remove(access(adminRole), owner.getId()))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("OWNER_REQUIRED");
        assertThat(owner.isActive()).isTrue();
    }

    @Test
    void memberOfAnotherOrganizationIsNotFound() {
        UUID foreignMemberId = UUID.randomUUID();
        when(members.findInOrganization(orgId, foreignMemberId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(access(ownerRole), foreignMemberId))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("MEMBER_NOT_FOUND");
    }

    private OrganizationMember member(Role role) {
        return OrganizationMember.active(orgId, UUID.randomUUID(), role.getId(), null, clock.instant());
    }

    private OrgAccess access(Role role) {
        return new OrgAccess(orgId, UUID.randomUUID(), UUID.randomUUID(), role.getId(), role.getName(),
                Set.of(Permissions.ORG_MEMBERS_MANAGE));
    }

    private static Role role(String name, boolean owner) {
        Role role = mock(Role.class);
        UUID id = UUID.randomUUID();
        lenient().when(role.getId()).thenReturn(id);
        lenient().when(role.getName()).thenReturn(name);
        lenient().when(role.isOwnerRole()).thenReturn(owner);
        return role;
    }
}
