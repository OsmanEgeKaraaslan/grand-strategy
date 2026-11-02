import { useId, useRef, useState, type InputHTMLAttributes, type ReactNode } from 'react'
import { Icon } from './icons'

type PasswordFieldProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'id'> & {
  label: ReactNode
}

/**
 * A labelled password field with an eye button that shows or hides what was typed. Hidden by
 * default; the button never submits the form, keeps the focus and the caret where they were, and
 * announces its state through its name. The label points at the input only, so the button is not
 * mistaken for the field. The value only ever lives in the input itself.
 */
export function PasswordField({ label, ...inputProps }: PasswordFieldProps) {
  const id = useId()
  const [visible, setVisible] = useState(false)
  const input = useRef<HTMLInputElement>(null)

  function toggle() {
    const field = input.current
    const start = field?.selectionStart ?? null
    const end = field?.selectionEnd ?? null
    setVisible((shown) => !shown)
    // Changing the type can reset the selection in some browsers: put the caret back.
    requestAnimationFrame(() => {
      if (field && start !== null && end !== null && document.activeElement === field) field.setSelectionRange(start, end)
    })
  }

  return (
    <div className="field">
      <label htmlFor={id}>
        <span>{label}</span>
      </label>
      <span className="password-input">
        <input {...inputProps} id={id} ref={input} type={visible ? 'text' : 'password'} />
        <button
          type="button"
          className="icon-button password-input__toggle"
          aria-label={visible ? 'Hide password' : 'Show password'}
          aria-pressed={visible}
          aria-controls={id}
          title={visible ? 'Hide password' : 'Show password'}
          // Keep the focus (and the caret) in the field while the button is pressed.
          onMouseDown={(event) => event.preventDefault()}
          onClick={toggle}
        >
          <Icon name={visible ? 'eyeOff' : 'eye'} size={16} />
        </button>
      </span>
    </div>
  )
}
