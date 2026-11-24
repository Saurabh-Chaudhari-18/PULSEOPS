import { api, type Session } from './client';
import type {
  AppService,
  Credential,
  Environment,
  Instructions,
  IntegrationStatus,
  IntegrationType,
  IssuedCredential,
  Member,
  OrgAccess,
  Organization,
  Page,
  Project,
  Role,
  Technology,
  User,
} from './types';

const pub = { auth: false } as const;

export const authApi = {
  login: (email: string, password: string) => api.post<Session>('/auth/login', { email, password }, pub),
  register: (fullName: string, email: string, password: string) =>
    api.post<{ status: 'active' | 'verification_pending' }>('/auth/register', { fullName, email, password }, pub),
  verifyEmail: (token: string) => api.post<void>('/auth/verify-email', { token }, pub),
  resendVerification: (email: string) => api.post<void>('/auth/resend-verification', { email }, pub),
  forgotPassword: (email: string) => api.post<void>('/auth/forgot-password', { email }, pub),
  resetPassword: (token: string, newPassword: string) =>
    api.post<void>('/auth/reset-password', { token, newPassword }, pub),
};

export const userApi = {
  me: () => api.get<User>('/users/me'),
  update: (fullName: string) => api.patch<User>('/users/me', { fullName }),
  changePassword: (currentPassword: string, newPassword: string) =>
    api.post<void>('/users/me/password', { currentPassword, newPassword }),
  revokeAllSessions: () => api.post<void>('/users/me/sessions/revoke-all'),
};

const org = (orgId: string) => `/organizations/${orgId}`;

export const orgApi = {
  list: () => api.get<Organization[]>('/organizations'),
  create: (name: string, description?: string) => api.post<Organization>('/organizations', { name, description }),
  get: (orgId: string) => api.get<Organization>(org(orgId)),
  access: (orgId: string) => api.get<OrgAccess>(`${org(orgId)}/access`),
  members: (orgId: string) => api.get<Page<Member>>(`${org(orgId)}/members`, { query: { size: 100 } }),
  addMember: (orgId: string, email: string, role: Role) =>
    api.post<{ memberId: string }>(`${org(orgId)}/members`, { email, role }),
  changeRole: (orgId: string, memberId: string, role: Role) =>
    api.patch<void>(`${org(orgId)}/members/${memberId}`, { role }),
  removeMember: (orgId: string, memberId: string) => api.delete<void>(`${org(orgId)}/members/${memberId}`),
  leave: (orgId: string) => api.post<void>(`${org(orgId)}/leave`),
};

export interface ProjectInput {
  name: string;
  description?: string;
  environment?: Environment;
  techStack?: string;
  region?: string;
}

export const projectApi = {
  list: (orgId: string, status: 'ACTIVE' | 'ARCHIVED', q?: string) =>
    api.get<Page<Project>>(`${org(orgId)}/projects`, { query: { status, q, size: 100 } }),
  get: (orgId: string, projectId: string) => api.get<Project>(`${org(orgId)}/projects/${projectId}`),
  create: (orgId: string, input: ProjectInput) => api.post<Project>(`${org(orgId)}/projects`, input),
  update: (orgId: string, projectId: string, input: Partial<ProjectInput>) =>
    api.patch<Project>(`${org(orgId)}/projects/${projectId}`, input),
  archive: (orgId: string, projectId: string) => api.post<Project>(`${org(orgId)}/projects/${projectId}/archive`),
  restore: (orgId: string, projectId: string) => api.post<Project>(`${org(orgId)}/projects/${projectId}/restore`),
  remove: (orgId: string, projectId: string, confirmName: string) =>
    api.delete<void>(`${org(orgId)}/projects/${projectId}`, { confirmName }),
};

export const serviceApi = {
  listForProject: (orgId: string, projectId: string) =>
    api.get<Page<AppService>>(`${org(orgId)}/projects/${projectId}/services`, { query: { size: 100 } }),
  get: (orgId: string, serviceId: string) => api.get<AppService>(`${org(orgId)}/services/${serviceId}`),
  create: (orgId: string, projectId: string, name: string, technology?: Technology, description?: string) =>
    api.post<AppService>(`${org(orgId)}/projects/${projectId}/services`, { name, technology, description }),
  archive: (orgId: string, serviceId: string) => api.post<AppService>(`${org(orgId)}/services/${serviceId}/archive`),
  restore: (orgId: string, serviceId: string) => api.post<AppService>(`${org(orgId)}/services/${serviceId}/restore`),
};

const integration = (orgId: string, serviceId: string) => `${org(orgId)}/services/${serviceId}/integration`;

export const integrationApi = {
  status: (orgId: string, serviceId: string) => api.get<IntegrationStatus>(integration(orgId, serviceId)),
  choose: (orgId: string, serviceId: string, type: IntegrationType) =>
    api.put<IntegrationStatus>(integration(orgId, serviceId), { type }),
  disable: (orgId: string, serviceId: string) =>
    api.post<IntegrationStatus>(`${integration(orgId, serviceId)}/disable`),
  enable: (orgId: string, serviceId: string) => api.post<IntegrationStatus>(`${integration(orgId, serviceId)}/enable`),
  instructions: (orgId: string, serviceId: string, type: IntegrationType) =>
    api.get<Instructions>(`${integration(orgId, serviceId)}/instructions`, { query: { type } }),
};

export const credentialApi = {
  list: (orgId: string, projectId: string) => api.get<Credential[]>(`${org(orgId)}/projects/${projectId}/credentials`),
  issue: (orgId: string, projectId: string, name: string, serviceId?: string, expiresInDays?: number) =>
    api.post<IssuedCredential>(`${org(orgId)}/projects/${projectId}/credentials`, { name, serviceId, expiresInDays }),
  revoke: (orgId: string, credentialId: string) =>
    api.post<Credential>(`${org(orgId)}/credentials/${credentialId}/revoke`),
  rotate: (orgId: string, credentialId: string, graceHours: number) =>
    api.post<IssuedCredential>(`${org(orgId)}/credentials/${credentialId}/rotate`, { graceHours }),
};
