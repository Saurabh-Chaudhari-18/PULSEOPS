import { createContext, useContext } from 'react';
import type { OrgAccess, Organization } from '../api/types';

export interface OrgContextValue {
  organization: Organization;
  organizations: Organization[];
  access: OrgAccess;
  can: (permission: string) => boolean;
}

export const OrgContext = createContext<OrgContextValue | null>(null);

export function useOrg(): OrgContextValue {
  const value = useContext(OrgContext);
  if (!value) throw new Error('useOrg must be used inside an organization route');
  return value;
}

export const P = {
  orgManage: 'org.manage',
  membersManage: 'org.members.manage',
  projectRead: 'project.read',
  projectManage: 'project.manage',
  credentialManage: 'credential.manage',
  telemetryRead: 'telemetry.read',
  incidentRead: 'incident.read',
  incidentManage: 'incident.manage',
} as const;
