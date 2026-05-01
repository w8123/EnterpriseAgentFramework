import { ref, watch } from 'vue'

type Theme = 'dark' | 'light'

const theme = ref<Theme>((localStorage.getItem('theme') as Theme) || 'dark')

function applyTheme(t: Theme) {
  const html = document.documentElement
  html.setAttribute('data-theme', t)
  if (t === 'dark') {
    html.classList.add('dark')
  } else {
    html.classList.remove('dark')
  }
  localStorage.setItem('theme', t)
}

// Apply on load
applyTheme(theme.value)

watch(theme, (t) => applyTheme(t))

export function useTheme() {
  function toggleTheme() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
  }

  return { theme, toggleTheme }
}
