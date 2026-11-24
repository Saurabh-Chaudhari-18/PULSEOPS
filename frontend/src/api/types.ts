export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface User {
  id: string;
  email: string;
  fullName: string;
  status: string;
  emailVerifiedAt: string | null;
  lastLoginAt: string | null;
  createdAt: string;
}

export type Role = 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER';

export interface Organization {
  id: string;
  name: string;
  slug: string;
  description: string | null;
  status: string;
  myRole: Role;
  createdAt: string;
}

export interface OrgAccess {
  organizationId: string;
  role: Role;
  permissions: string[];
}

export interface Member {
  memberId: string;
  userId: string;
  email: string;
  fullName: string;
  roleId: string;
  role: Role;
  status: 'INVITED' | 'ACTIVE' | 'REMOVED';
  joinedAt: string | null;
}

export type Environment = 'DEVELOPMENT' | 'TESTING' | 'STAGING' | 'PRODUCTION' | 'OTHER';

export interface Project {
  id: string;
  name: string;
  description: string | null;
  techStack: string | null;
  environment: Environment | null;
  region: string | null;
  applicationType: string | null;
  status: 'ACTIVE' | 'ARCHIVED';
  serviceCount: number;
  connectedServiceCount: number;
  createdAt: string;
  updatedAt: string;
}

export type Technology =
  | 'JAVA_SPRING_BOOT'
  | 'PYTHON'
  | 'NODEJS'
  | 'DOTNET'
  | 'GO'
  | 'CUSTOM_REST'
  | 'OPENTELEMETRY'
  | 'OTHER';

export type IntegrationType = 'JAVA_AGENT' | 'REST_API' | 'OTLP';

export type ConnectionStatus = 'NOT_CONNECTED' | 'WAITING_FOR_DATA' | 'CONNECTED' | 'CONNECTION_ERROR' | 'DISABLED';

export interface ServiceConnection {
  integrationType: IntegrationType | null;
  status: ConnectionStatus;
  firstTelemetryAt: string | null;
  lastTelemetryAt: string | null;
  lastError: string | null;
}

export interface AppService {
  id: string;
  projectId: string;
  projectName: string;
  name: string;
  description: string | null;
  technology: Technology | null;
  status: 'ACTIVE' | 'ARCHIVED';
  connection: ServiceConnection | null;
  createdAt: string;
}

export interface IntegrationStatus {
  serviceId: string;
  integrationType: IntegrationType | null;
  status: ConnectionStatus;
  firstTelemetryAt: string | null;
  lastTelemetryAt: string | null;
  lastError: string | null;
  receivingNow: boolean;
  usableKeys: number;
  nextStep: string;
}

export interface InstructionStep {
  title: string;
  description: string;
  snippet: string | null;
}

export interface Instructions {
  type: IntegrationType;
  available: boolean;
  unavailableReason: string | null;
  endpoint: string | null;
  steps: InstructionStep[];
}

export interface Credential {
  id: string;
  projectId: string;
  serviceId: string | null;
  name: string;
  keyPrefix: string;
  status: 'ACTIVE' | 'REVOKED' | 'EXPIRED';
  expiresAt: string | null;
  revokedAt: string | null;
  lastUsedAt: string | null;
  rotatedFromId: string | null;
  createdAt: string;
}

export interface IssuedCredential {
  credential: Credential;
  key: string;
  notice: string;
}
