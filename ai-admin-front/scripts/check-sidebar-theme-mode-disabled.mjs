import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const root = process.cwd()
const sidebar = readFileSync(resolve(root, 'src/components/common/AppSidebar.vue'), 'utf8')
const themeSource = readFileSync(resolve(root, 'src/composables/useTheme.ts'), 'utf8')

const failures = []

if (!sidebar.includes('const themeModeControlsDisabled = true')) {
  failures.push('sidebar should explicitly freeze theme mode controls while dark mode is unfinished')
}

if (sidebar.includes("@click=\"setThemeMode('light')\"") || sidebar.includes("@click=\"setThemeMode('dark')\"")) {
  failures.push('theme mode buttons should not call setThemeMode while controls are frozen')
}

if (!sidebar.includes(':disabled="themeModeControlsDisabled"')) {
  failures.push('theme mode buttons should be disabled in the settings panel')
}

if (!sidebar.includes(':aria-disabled="themeModeControlsDisabled"')) {
  failures.push('theme mode buttons should expose disabled state to assistive technologies')
}

if (!sidebar.includes('&:disabled')) {
  failures.push('disabled theme mode buttons should have an explicit disabled style')
}

if (!themeSource.includes("const lockedTheme: Theme = 'light'")) {
  failures.push('theme composable should lock display mode to light while dark mode is unfinished')
}

if (themeSource.includes("localStorage.getItem('theme')")) {
  failures.push('theme composable should ignore previously stored dark mode while mode controls are frozen')
}

if (themeSource.includes("theme.value === 'dark' ? 'light' : 'dark'")) {
  failures.push('toggleTheme should not switch to dark while mode controls are frozen')
}

if (!themeSource.includes('theme.value = lockedTheme')) {
  failures.push('toggleTheme should be a no-op that keeps the locked light mode')
}

if (failures.length) {
  console.error(failures.join('\n'))
  process.exit(1)
}

console.log('Sidebar theme mode controls are frozen.')
