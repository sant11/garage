import { test as setup } from '@playwright/test';
import path from 'node:path';

/**
 * Authentication setup — runs once before the test projects that depend on it,
 * logs in through Spring Security's form-login, and persists the session to
 * storageState. Real specs load that state and start already authenticated, so
 * no test re-drives the login UI (E2E rule: authenticate without the UI).
 */

const authFile = path.resolve(__dirname, '..', 'auth.json');

// Dev owner credentials — a matched BCrypt pair documented in
// context/archive/2026-05-26-access-control-foundation/dev-credentials.md.
// NOT a real secret: the local-dev fallback of the OWNER_USERNAME /
// OWNER_PASSWORD_HASH env vars (see application.properties).
const USERNAME = process.env.OWNER_USERNAME ?? 'owner';
const PASSWORD = process.env.OWNER_PASSWORD ?? 'owner-local-dev';

setup('authenticate as owner', async ({ page }) => {
	await page.goto('/login');

	// Vaadin <vaadin-login-form> uses open shadow DOM, which Playwright pierces,
	// so the component's default i18n labels resolve directly. If the form is
	// ever re-themed/relabeled, update these three locators.
	await page.getByLabel('Username').fill(USERNAME);
	await page.getByLabel('Password').fill(PASSWORD);
	await page.getByRole('button', { name: 'Log in' }).click();

	// A successful form-login leaves /login (Spring redirects to the saved
	// request or the app root). Landing anywhere off /login proves we are in.
	await page.waitForURL((url) => !url.pathname.startsWith('/login'));

	await page.context().storageState({ path: authFile });
});
