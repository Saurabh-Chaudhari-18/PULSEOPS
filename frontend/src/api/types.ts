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

// ---------------------------------------------------------------- monitoring

export type HealthStatus = 'HEALTHY' | 'WARNING' | 'CRITICAL' | 'NO_DATA';

export interface HealthAssessment {
  status: HealthStatus;
  reasons: string[];
}

export interface Window {
  from: string;
  to: string;
  stepSeconds: number;
}

export interface ServiceOverview {
  serviceId: string;
  serviceName: string;
  projectId: string;
  projectName: string;
  connectionStatus: ConnectionStatus;
  lastTelemetryAt: string | null;
  requests: number;
  requestsPerSecond: number | null;
  errorRate: number | null;
  p95Ms: number | null;
  health: HealthAssessment;
}

export interface DatabaseTotals {
  calls: number;
  errors: number;
  errorRate: number | null;
  slowCalls: number;
  p95Ms: number | null;
  health: HealthAssessment;
}

export interface OrganizationOverview {
  window: Window;
  services: number;
  connectedServices: number;
  requests: number;
  requestsPerSecond: number | null;
  errorRate: number | null;
  p95Ms: number | null;
  servicesByHealth: Partial<Record<HealthStatus, number>>;
  database: DatabaseTotals;
  attention: ServiceOverview[];
  allServices: ServiceOverview[];
}

export interface ServiceSummary {
  serviceId: string;
  serviceName: string;
  connectionStatus: ConnectionStatus;
  lastTelemetryAt: string | null;
  window: Window;
  requests: number;
  requestsPerSecond: number | null;
  serverErrors: number;
  clientErrors: number;
  errorRate: number | null;
  avgMs: number | null;
  p50Ms: number | null;
  p95Ms: number | null;
  p99Ms: number | null;
  maxMs: number | null;
  statusClasses: Record<string, number>;
  health: HealthAssessment;
}

export interface SeriesPoint {
  time: string;
  requests: number;
  requestsPerSecond: number | null;
  serverErrors: number;
  clientErrors: number;
  errorRate: number | null;
  avgMs: number | null;
  p50Ms: number | null;
  p95Ms: number | null;
  p99Ms: number | null;
}

export interface TimeSeries {
  serviceId: string;
  method: string | null;
  route: string | null;
  window: Window;
  points: SeriesPoint[];
}

export interface EndpointView {
  method: string;
  route: string;
  requests: number;
  requestsPerSecond: number | null;
  serverErrors: number;
  clientErrors: number;
  errorRate: number | null;
  avgMs: number | null;
  p50Ms: number | null;
  p95Ms: number | null;
  p99Ms: number | null;
  maxMs: number | null;
  lastSeen: string | null;
}

export interface EndpointList {
  serviceId: string;
  window: Window;
  sort: string;
  totalEndpoints: number;
  endpoints: EndpointView[];
}

export interface RuntimePoint {
  time: string;
  cpuUtilization: number | null;
  heapUsedBytes: number | null;
  heapLimitBytes: number | null;
  threads: number | null;
  loadedClasses: number | null;
}

export interface RuntimeMetrics {
  serviceId: string;
  window: Window;
  available: boolean;
  metricNames: string[];
  points: RuntimePoint[];
}

export interface DbBreakdown {
  dbSystem: string;
  operationType: string;
  calls: number;
  errors: number;
  totalMs: number;
  shareOfTime: number | null;
}

export interface DatabaseSummary {
  serviceId: string;
  window: Window;
  slowThresholdMs: number;
  calls: number;
  callsPerSecond: number | null;
  errors: number;
  errorRate: number | null;
  slowCalls: number;
  totalMs: number;
  avgMs: number | null;
  p50Ms: number | null;
  p95Ms: number | null;
  p99Ms: number | null;
  maxMs: number | null;
  breakdown: DbBreakdown[];
  health: HealthAssessment;
}

export interface DbOperation {
  dbSystem: string | null;
  operationType: string | null;
  operation: string;
  table: string | null;
  calls: number;
  callsPerSecond: number | null;
  errors: number;
  errorRate: number | null;
  slowCalls: number;
  totalMs: number;
  shareOfTime: number | null;
  avgMs: number | null;
  p50Ms: number | null;
  p95Ms: number | null;
  p99Ms: number | null;
  maxMs: number | null;
  lastSeen: string | null;
  slowestTraceId: string | null;
}

export interface DbOperationList {
  serviceId: string;
  window: Window;
  sort: string;
  slowThresholdMs: number;
  totalOperations: number;
  operations: DbOperation[];
}

export interface DbSeriesPoint {
  time: string;
  calls: number;
  callsPerSecond: number | null;
  errors: number;
  slowCalls: number;
  avgMs: number | null;
  p50Ms: number | null;
  p95Ms: number | null;
  p99Ms: number | null;
}

export interface DbSeries {
  serviceId: string;
  operation: string | null;
  window: Window;
  slowThresholdMs: number;
  points: DbSeriesPoint[];
}

export interface DbCall {
  occurredAt: string;
  dbSystem: string | null;
  operationType: string | null;
  operation: string;
  table: string | null;
  durationMs: number;
  error: boolean;
  errorMessage: string | null;
  traceId: string | null;
  spanId: string | null;
}

export interface DbCallList {
  serviceId: string;
  window: Window;
  minDurationMs: number;
  errorsOnly: boolean;
  calls: DbCall[];
}

export interface PoolPoint {
  time: string;
  used: number | null;
  idle: number | null;
  max: number | null;
  pending: number | null;
  peakUsed: number | null;
}

export interface ConnectionPool {
  serviceId: string;
  window: Window;
  available: boolean;
  pools: string[];
  points: PoolPoint[];
}
