import { PasswordField } from '../components/PasswordInput'

/** "New password" and "Confirm new password" inputs, each with a show/hide control. */
export function NewPasswordFields({
  password,
  confirmation,
  onPassword,
  onConfirmation,
  label = 'New password',
}: {
  password: string
  confirmation: string
  onPassword: (value: string) => void
  onConfirmation: (value: string) => void
  label?: string
}) {
  return (
    <>
      <PasswordField label={label} autoComplete="new-password" value={password} onChange={(e) => onPassword(e.target.value)} />
      <PasswordField
        label={`Confirm ${label.toLowerCase()}`}
        autoComplete="new-password"
        value={confirmation}
        onChange={(e) => onConfirmation(e.target.value)}
      />
    </>
  )
}
