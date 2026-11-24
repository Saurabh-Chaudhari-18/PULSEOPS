import { createBrowserRouter, Navigate, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CssBaseline, ThemeProvider } from '@mui/material';
import { isApiError } from './api/client';
import { AuthProvider } from './auth/AuthContext';
import { RedirectIfSignedIn, RequireAuth } from './auth/RequireAuth';
import { AppLayout } from './layout/AppLayout';
import { AccountPage } from './pages/account/AccountPage';
import { LoginPage } from './pages/auth/LoginPage';
import { ForgotPasswordPage, ResetPasswordPage, VerifyEmailPage } from './pages/auth/RecoveryPages';
import { RegisterPage } from './pages/auth/RegisterPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { CreateOrganizationPage } from './pages/org/CreateOrganizationPage';
import { HomeRedirect } from './pages/org/HomeRedirect';
import { ProjectPage } from './pages/projects/ProjectPage';
import { ProjectsPage } from './pages/projects/ProjectsPage';
import { OverviewPage } from './pages/overview/OverviewPage';
import { ServiceConnectionTab } from './pages/services/ServiceConnectionTab';
import { ServiceDatabaseTab } from './pages/services/ServiceDatabaseTab';
import { ServicePerformanceTab } from './pages/services/ServicePerformanceTab';
import { ServiceShell } from './pages/services/ServiceShell';
import { TeamPage } from './pages/team/TeamPage';
import { theme } from './theme';

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 15_000,
        refetchOnWindowFocus: true,
        // Client errors (404, 403, validation) will not fix themselves: do not retry them.
        retry: (count, error) => !(isApiError(error) && error.status >= 400 && error.status < 500) && count < 2,
      },
    },
  });
}

const router = createBrowserRouter([
  {
    path: '/login',
    element: (
      <RedirectIfSignedIn>
        <LoginPage />
      </RedirectIfSignedIn>
    ),
  },
  {
    path: '/register',
    element: (
      <RedirectIfSignedIn>
        <RegisterPage />
      </RedirectIfSignedIn>
    ),
  },
  { path: '/forgot-password', element: <ForgotPasswordPage /> },
  { path: '/reset-password', element: <ResetPasswordPage /> },
  { path: '/verify-email', element: <VerifyEmailPage /> },
  {
    element: <RequireAuth />,
    children: [
      { path: '/', element: <HomeRedirect /> },
      { path: '/organizations/new', element: <CreateOrganizationPage /> },
      {
        path: '/o/:orgId',
        element: <AppLayout />,
        children: [
          { index: true, element: <Navigate to="overview" replace /> },
          { path: 'overview', element: <OverviewPage /> },
          { path: 'projects', element: <ProjectsPage /> },
          { path: 'projects/:projectId', element: <ProjectPage /> },
          {
            path: 'services/:serviceId',
            element: <ServiceShell />,
            children: [
              { index: true, element: <ServicePerformanceTab /> },
              { path: 'database', element: <ServiceDatabaseTab /> },
              { path: 'connection', element: <ServiceConnectionTab /> },
            ],
          },
          { path: 'team', element: <TeamPage /> },
          { path: 'account', element: <AccountPage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
]);

const queryClient = createQueryClient();

export function App() {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <RouterProvider router={router} />
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );
}
