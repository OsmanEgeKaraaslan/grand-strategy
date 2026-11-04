import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useCaptcha } from '../auth/useCaptcha'
import { Captcha } from './Captcha'

/** A fake Turnstile: render() remembers the callback so the test can "solve" the check. */
function fakeTurnstile() {
  const widget: { solve?: (token: string) => void } = {}
  window.turnstile = {
    render: vi.fn((_element: HTMLElement, options: Record<string, unknown>) => {
      widget.solve = options.callback as (token: string) => void
      return 'widget-1'
    }),
    reset: vi.fn(),
    remove: vi.fn(),
  }
  return widget
}

function Form({ siteKey, onSubmit }: { siteKey?: string; onSubmit: (token: string | undefined) => void }) {
  const captcha = useCaptcha(siteKey)
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        onSubmit(captcha.consume())
      }}
    >
      <Captcha siteKey={siteKey} onToken={captcha.setToken} resetSignal={captcha.resetSignal} />
      <button type="submit" disabled={!captcha.ready}>
        Send
      </button>
    </form>
  )
}

afterEach(() => {
  delete window.turnstile
})

describe('Captcha (Turnstile)', () => {
  it('blocks the form until the check is solved, then sends the token once and asks for a fresh one', async () => {
    const widget = fakeTurnstile()
    const submit = vi.fn()
    render(<Form siteKey="site-key" onSubmit={submit} />)

    expect(await screen.findByLabelText('Human check')).toBeInTheDocument()
    expect(window.turnstile!.render).toHaveBeenCalledWith(expect.any(HTMLElement), expect.objectContaining({ sitekey: 'site-key' }))
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled()

    act(() => widget.solve!('token-1'))
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(submit).toHaveBeenCalledWith('token-1')
    // A token works once: the widget is reset and the form waits for a new one.
    expect(window.turnstile!.reset).toHaveBeenCalledWith('widget-1')
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled()
  })

  it('does nothing without a site key (local development)', async () => {
    const submit = vi.fn()
    render(<Form onSubmit={submit} />)
    expect(screen.queryByLabelText('Human check')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))
    expect(submit).toHaveBeenCalledWith(undefined)
  })
})
