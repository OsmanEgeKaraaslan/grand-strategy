/** Password rules shared by sign-up, recovery and the account page (Supabase enforces its own minimum too). */
export const MIN_PASSWORD_LENGTH = 8

export function checkNewPassword(password: string, confirmation: string): string | null {
  if (password.length < MIN_PASSWORD_LENGTH) return `Passwords need at least ${MIN_PASSWORD_LENGTH} characters.`
  if (password !== confirmation) return 'The passwords do not match.'
  return null
}
