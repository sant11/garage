import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';

/**
 * Playwright E2E project for GarageOps (Spring Boot 4 + Vaadin Flow 25).
 *
 * Isolated in /e2e so its Node toolchain (package.json, node_modules) never
 * collides with the package.json Vaadin generates at the repo root during the
 * Maven build.
 *
 * The app is owner-gated (Vaadin form login) and needs Postgres on
 * localhost:5433 to boot. The `setup` project logs in once and persists
 * storageState to e2e/auth.json (gitignored); real specs reuse it so they
 * authenticate WITHOUT driving the login UI on every test (E2E rule:
 * authenticate without the UI).
 */

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:8080';
const REPO_ROOT = path.resolve(__dirname, '..');
const AUTH_FILE = path.resolve(__dirname, 'auth.json');

export default defineConfig({
	testDir: './tests',
	fullyParallel: true,
	forbidOnly: !!process.env.CI,
	retries: process.env.CI ? 2 : 0,
	workers: process.env.CI ? 1 : undefined,
	reporter: 'html',

	use: {
		baseURL: BASE_URL,
		trace: 'on-first-retry',
		screenshot: 'only-on-failure',
	},

	projects: [
		// Logs in once; everything below reuses its storageState.
		{ name: 'setup', testMatch: /.*\.setup\.ts/ },
		{
			name: 'chromium',
			use: { ...devices['Desktop Chrome'], storageState: AUTH_FILE },
			dependencies: ['setup'],
		},
	],

	// Readiness probe is the one anonymous-accessible endpoint — SecurityConfig
	// permits ONLY /actuator/health (privacy NFR keeps /actuator/** closed).
	// If the app is already running on 8080, Playwright reuses it; otherwise it
	// starts it via the Maven wrapper. Note: the app needs JDK 21 on JAVA_HOME
	// and Postgres on localhost:5433 — easiest to start it yourself with
	// `mvnw.cmd spring-boot:run` and let `reuseExistingServer` pick it up.
	webServer: {
		command: 'mvnw.cmd spring-boot:run',
		cwd: REPO_ROOT,
		url: `${BASE_URL}/actuator/health`,
		reuseExistingServer: true,
		timeout: 180_000,
		stdout: 'pipe',
		stderr: 'pipe',
	},
});
