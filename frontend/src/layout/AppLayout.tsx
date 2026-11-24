import { useEffect, useMemo, useState } from 'react';
import { Link as RouterLink, NavLink, Outlet, useNavigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Avatar,
  Box,
  Button,
  Divider,
  Drawer,
  IconButton,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
  Typography,
  useMediaQuery,
} from '@mui/material';
import MenuIcon from '@mui/icons-material/Menu';
import UnfoldMoreIcon from '@mui/icons-material/UnfoldMore';
import CheckIcon from '@mui/icons-material/Check';
import AddIcon from '@mui/icons-material/Add';
import { orgApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { ErrorNotice, Loading } from '../components/Feedback';
import { PulseMark } from '../components/PulseMark';
import { rememberOrganization } from '../lib/storage';
import { roleLabel } from '../lib/format';
import { tokens } from '../theme';
import { OrgContext, P } from './OrgContext';
import { isApiError } from '../api/client';

const SIDEBAR = 236;

interface NavItem {
  label: string;
  to: string;
  permission?: string;
}

interface NavGroup {
  label: string;
  items: NavItem[];
}

function navGroups(orgId: string): NavGroup[] {
  const base = `/o/${orgId}`;
  return [
    {
      label: 'Observe',
      items: [{ label: 'Overview', to: `${base}/overview`, permission: P.telemetryRead }],
    },
    {
      label: 'Set up',
      items: [
        { label: 'Projects and services', to: `${base}/projects`, permission: P.projectRead },
        { label: 'Team', to: `${base}/team` },
      ],
    },
  ];
}

function Sidebar({ orgId, can, onNavigate }: { orgId: string; can: (p: string) => boolean; onNavigate?: () => void }) {
  return (
    <Box
      component="nav"
      aria-label="Main"
      sx={{
        width: SIDEBAR,
        height: '100%',
        bgcolor: tokens.ink,
        color: tokens.sidebarText,
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25, px: 2.25, height: 60 }}>
        <PulseMark />
        <Typography sx={{ color: '#FFFFFF', fontWeight: 600, fontSize: 16, letterSpacing: '-0.01em' }}>
          PulseOps
        </Typography>
      </Box>
      <Box sx={{ flex: 1, overflowY: 'auto', px: 1.25, pt: 1 }}>
        {navGroups(orgId)
          .filter((group) => group.items.some((item) => !item.permission || can(item.permission)))
          .map((group) => (
            <Box key={group.label} sx={{ mb: 2 }}>
              <Typography sx={{ px: 1.25, pb: 0.75, fontSize: 12, color: '#8592A8', fontWeight: 500 }}>
                {group.label}
              </Typography>
              {group.items
                .filter((item) => !item.permission || can(item.permission))
                .map((item) => (
                  <Box
                    key={item.to}
                    component={NavLink}
                    to={item.to}
                    onClick={onNavigate}
                    sx={{
                      display: 'block',
                      px: 1.25,
                      py: 0.9,
                      borderRadius: 1,
                      color: tokens.sidebarText,
                      textDecoration: 'none',
                      fontSize: 14,
                      '&:hover': { bgcolor: '#1F2A40', color: '#FFFFFF' },
                      '&.active': {
                        bgcolor: tokens.sidebarActive,
                        color: '#FFFFFF',
                        boxShadow: `inset 3px 0 0 ${tokens.pulse}`,
                      },
                    }}
                  >
                    {item.label}
                  </Box>
                ))}
            </Box>
          ))}
      </Box>
    </Box>
  );
}

export function AppLayout() {
  const { orgId = '' } = useParams();
  const navigate = useNavigate();
  const { user, signOut } = useAuth();
  const wide = useMediaQuery('(min-width: 960px)');
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [orgMenu, setOrgMenu] = useState<HTMLElement | null>(null);
  const [userMenu, setUserMenu] = useState<HTMLElement | null>(null);

  const organizations = useQuery({ queryKey: ['organizations'], queryFn: orgApi.list });
  const access = useQuery({ queryKey: ['access', orgId], queryFn: () => orgApi.access(orgId), enabled: !!orgId });

  useEffect(() => {
    if (access.data) rememberOrganization(orgId);
  }, [access.data, orgId]);

  const organization = organizations.data?.find((o) => o.id === orgId);
  const context = useMemo(() => {
    if (!organization || !access.data) return null;
    const permissions = new Set(access.data.permissions);
    return {
      organization,
      organizations: organizations.data ?? [],
      access: access.data,
      can: (p: string) => permissions.has(p),
    };
  }, [organization, organizations.data, access.data]);

  if (organizations.isPending || access.isPending) {
    return (
      <Box sx={{ p: 4 }}>
        <Loading label="Opening organization" />
      </Box>
    );
  }
  if (access.error && isApiError(access.error) && access.error.status === 404) {
    return (
      <Box sx={{ p: 4, maxWidth: 520 }}>
        <Typography variant="h2" gutterBottom>
          Organization not found
        </Typography>
        <Typography sx={{ mb: 2 }}>It does not exist or you are not a member of it.</Typography>
        <Button variant="contained" component={RouterLink} to="/">
          Go to my organizations
        </Button>
      </Box>
    );
  }
  if (organizations.error || access.error || !context) {
    return (
      <Box sx={{ p: 4 }}>
        <ErrorNotice
          error={organizations.error ?? access.error}
          onRetry={() => {
            void organizations.refetch();
            void access.refetch();
          }}
        />
      </Box>
    );
  }

  const sidebar = <Sidebar orgId={orgId} can={context.can} onNavigate={() => setDrawerOpen(false)} />;
  const initials = (user?.fullName || user?.email || '?')
    .split(/\s+/)
    .map((part) => part[0])
    .join('')
    .slice(0, 2)
    .toUpperCase();

  return (
    <OrgContext.Provider value={context}>
      <Box sx={{ display: 'flex', minHeight: '100vh' }}>
        {wide ? (
          <Box sx={{ width: SIDEBAR, flexShrink: 0, position: 'sticky', top: 0, height: '100vh' }}>{sidebar}</Box>
        ) : (
          <Drawer open={drawerOpen} onClose={() => setDrawerOpen(false)}>
            {sidebar}
          </Drawer>
        )}
        <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
          <Box
            component="header"
            sx={{
              height: 60,
              display: 'flex',
              alignItems: 'center',
              gap: 1,
              px: { xs: 1.5, md: 3 },
              borderBottom: 1,
              borderColor: 'divider',
              bgcolor: 'background.paper',
              position: 'sticky',
              top: 0,
              zIndex: 10,
            }}
          >
            {!wide && (
              <IconButton aria-label="Open navigation" onClick={() => setDrawerOpen(true)}>
                <MenuIcon />
              </IconButton>
            )}
            <Button
              onClick={(e) => setOrgMenu(e.currentTarget)}
              endIcon={<UnfoldMoreIcon fontSize="small" />}
              aria-label="Switch organization"
              sx={{ color: tokens.ink, fontWeight: 600, maxWidth: 360, justifyContent: 'flex-start' }}
            >
              <Box component="span" sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {context.organization.name}
              </Box>
            </Button>
            <Typography variant="caption" sx={{ display: { xs: 'none', sm: 'block' } }}>
              {roleLabel[context.access.role] ?? context.access.role}
            </Typography>
            <Box sx={{ flex: 1 }} />
            <IconButton onClick={(e) => setUserMenu(e.currentTarget)} aria-label="Account menu">
              <Avatar
                sx={{
                  width: 32,
                  height: 32,
                  fontSize: 13,
                  bgcolor: tokens.cobaltSoft,
                  color: tokens.cobalt,
                  fontWeight: 600,
                }}
              >
                {initials}
              </Avatar>
            </IconButton>
          </Box>

          <Menu anchorEl={orgMenu} open={!!orgMenu} onClose={() => setOrgMenu(null)}>
            {context.organizations.map((o) => (
              <MenuItem
                key={o.id}
                selected={o.id === orgId}
                onClick={() => {
                  setOrgMenu(null);
                  navigate(`/o/${o.id}`);
                }}
              >
                <ListItemIcon>{o.id === orgId && <CheckIcon fontSize="small" />}</ListItemIcon>
                <ListItemText primary={o.name} secondary={roleLabel[o.myRole] ?? o.myRole} />
              </MenuItem>
            ))}
            <Divider />
            <MenuItem
              onClick={() => {
                setOrgMenu(null);
                navigate('/organizations/new');
              }}
            >
              <ListItemIcon>
                <AddIcon fontSize="small" />
              </ListItemIcon>
              New organization
            </MenuItem>
          </Menu>

          <Menu anchorEl={userMenu} open={!!userMenu} onClose={() => setUserMenu(null)}>
            <Box sx={{ px: 2, py: 1, maxWidth: 280 }}>
              <Typography variant="h4">{user?.fullName}</Typography>
              <Typography variant="caption">{user?.email}</Typography>
            </Box>
            <Divider />
            <MenuItem
              onClick={() => {
                setUserMenu(null);
                navigate(`/o/${orgId}/account`);
              }}
            >
              Account settings
            </MenuItem>
            <MenuItem
              onClick={async () => {
                setUserMenu(null);
                await signOut();
                navigate('/login', { replace: true });
              }}
            >
              Sign out
            </MenuItem>
          </Menu>

          <Box
            component="main"
            sx={{ flex: 1, px: { xs: 2, md: 4 }, py: { xs: 2.5, md: 4 }, maxWidth: 1240, width: '100%' }}
          >
            <Outlet />
          </Box>
        </Box>
      </Box>
    </OrgContext.Provider>
  );
}
