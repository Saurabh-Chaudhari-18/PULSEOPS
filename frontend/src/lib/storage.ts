/** Light per-browser preferences. Storage can be unavailable (private mode, policies): never throw. */
const LAST_ORG = 'pulseops.lastOrganization';

export function rememberOrganization(orgId: string) {
  try {
    window.localStorage.setItem(LAST_ORG, orgId);
  } catch {
    // ignore
  }
}

export function lastOrganization(): string | null {
  try {
    return window.localStorage.getItem(LAST_ORG);
  } catch {
    return null;
  }
}
