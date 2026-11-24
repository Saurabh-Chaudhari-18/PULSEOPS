import { Link as RouterLink, NavLink, Outlet, useLocation, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Box, Chip, Link } from '@mui/material';
import { serviceApi } from '../../api/endpoints';
import { ErrorNotice, Loading } from '../../components/Feedback';
import { PageHeader } from '../../components/PageHeader';
import { useOrg } from '../../layout/OrgContext';
import { technologyLabel } from '../../lib/format';
import { tokens } from '../../theme';

/** A service: header, then tabs for its performance, its database and its connection. */
export function ServiceShell() {
  const { serviceId = '' } = useParams();
  const { organization } = useOrg();
  const orgId = organization.id;
  const { search } = useLocation();
  const service = useQuery({
    queryKey: ['service', orgId, serviceId],
    queryFn: () => serviceApi.get(orgId, serviceId),
  });

  if (service.isPending) return <Loading label="Loading service" />;
  if (service.isError) return <ErrorNotice error={service.error} onRetry={() => void service.refetch()} />;
  const s = service.data;
  const base = `/o/${orgId}/services/${serviceId}`;
  // Keep the chosen time range when switching tabs.
  const range = new URLSearchParams(search).get('range');
  const keep = range ? `?range=${range}` : '';
  const tabs = [
    { label: 'Performance', to: base, end: true },
    { label: 'Database', to: `${base}/database`, end: false },
    { label: 'Connection', to: `${base}/connection`, end: false },
  ];

  return (
    <>
      <PageHeader
        above={
          <Link component={RouterLink} to={`/o/${orgId}/projects/${s.projectId}`} variant="body2">
            {s.projectName}
          </Link>
        }
        title={
          <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
            {s.name}
            {s.status === 'ARCHIVED' && <Chip size="small" color="warning" variant="outlined" label="Archived" />}
          </Box>
        }
        description={
          [s.technology ? technologyLabel[s.technology] : null, s.description].filter(Boolean).join(' — ') || undefined
        }
      />
      <Box
        component="nav"
        aria-label="Service sections"
        sx={{ display: 'flex', gap: 3, borderBottom: 1, borderColor: 'divider', mb: 3, mt: -1 }}
      >
        {tabs.map((tab) => (
          <Box
            key={tab.label}
            component={NavLink}
            to={tab.to + (tab.label === 'Connection' ? '' : keep)}
            end={tab.end}
            sx={{
              py: 1.25,
              fontSize: 14,
              fontWeight: 600,
              color: tokens.muted,
              textDecoration: 'none',
              borderBottom: '2px solid transparent',
              mb: '-1px',
              '&:hover': { color: tokens.ink },
              '&.active': { color: tokens.ink, borderBottomColor: tokens.cobalt },
            }}
          >
            {tab.label}
          </Box>
        ))}
      </Box>
      <Outlet />
    </>
  );
}
