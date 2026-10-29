/** "New password" and "Confirm new password" inputs. */
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
      <label className="field">
        <span>{label}</span>
        <input type="password" autoComplete="new-password" value={password} onChange={(e) => onPassword(e.target.value)} />
      </label>
      <label className="field">
        <span>Confirm {label.toLowerCase()}</span>
        <input
          type="password"
          autoComplete="new-password"
          value={confirmation}
          onChange={(e) => onConfirmation(e.target.value)}
        />
      </label>
    </>
  )
}
