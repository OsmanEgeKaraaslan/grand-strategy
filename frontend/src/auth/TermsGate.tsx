import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../api/ApiContext'
import { ApiError, describeError } from '../api/client'
import { queryKeys, useMe } from '../api/queries'
import { Alert } from '../components/Alert'
import { AuthFrame } from '../components/AppShell'
import { Spinner } from '../components/Spinner'
import { useAuth } from './AuthContext'

/**
 * Shows the game only to players who accepted the current Terms of Service and Privacy Policy
 * (Phase 15A). Accounts created before acceptance existed, and everyone after a new version, see the
 * acceptance screen first; the server refuses game requests until then as well.
 */
export function TermsGate({ children }: { children: ReactNode }) {
  const me = useMe()
  if (me.isPending) return <Spinner label="Loading your account…" />
  if (me.error instanceof ApiError && me.error.code === 'ACCOUNT_SUSPENDED') return <Suspended message={me.error.message} />
  const terms = me.data?.terms
  if (!terms || terms.accepted) return <>{children}</>
  return <AcceptTerms termsVersion={terms.currentTermsVersion} privacyVersion={terms.currentPrivacyVersion} reaccept={terms.acceptedTermsVersion !== null} />
}

/** An administrator suspended the account (Q-MOD1): the server refuses it, so only signing out is left. */
function Suspended({ message }: { message: string }) {
  const { signOut } = useAuth()
  const queryClient = useQueryClient()
  return (
    <AuthFrame title="Account suspended" subtitle="This account can no longer play.">
      <div className="form">
        <Alert>{message}</Alert>
        <p className="muted">
          If you think this is a mistake, write to the contact address in the{' '}
          <Link to="/legal/privacy">Privacy Policy</Link>.
        </p>
        <button
          type="button"
          className="button button--primary"
          onClick={async () => {
            await signOut()
            queryClient.clear()
          }}
        >
          Log out
        </button>
      </div>
    </AuthFrame>
  )
}

function AcceptTerms({ termsVersion, privacyVersion, reaccept }: { termsVersion: string; privacyVersion: string; reaccept: boolean }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [agreed, setAgreed] = useState(false)
  const accept = useMutation({
    mutationFn: () => api.acceptTerms(termsVersion, privacyVersion),
    onSuccess: (profile) => queryClient.setQueryData(queryKeys.me, profile),
    // A newer version was published meanwhile: reload it so the player sees what they accept.
    onError: () => queryClient.invalidateQueries({ queryKey: queryKeys.me }),
  })

  return (
    <AuthFrame
      title={reaccept ? 'Our terms have changed' : 'Before you play'}
      subtitle="Please accept the Terms of Service and read the Privacy Policy."
    >
      <div className="form">
        {accept.isError && <Alert>{describeError(accept.error)}</Alert>}
        <p className="muted">
          Read the{' '}
          <Link to="/legal/terms" target="_blank">
            Terms of Service
          </Link>{' '}
          (version {termsVersion}), the{' '}
          <Link to="/legal/privacy" target="_blank">
            Privacy Policy
          </Link>{' '}
          (version {privacyVersion}) and the{' '}
          <Link to="/legal/cookies" target="_blank">
            Cookie and Storage Notice
          </Link>
          .
        </p>
        <label className="checkbox-field">
          <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} />
          <span>I accept the Terms of Service and have read the Privacy Policy.</span>
        </label>
        <button
          type="button"
          className="button button--primary"
          disabled={!agreed || accept.isPending}
          onClick={() => accept.mutate()}
        >
          {accept.isPending ? 'Saving…' : 'Accept and continue'}
        </button>
        <p className="muted">
          Do not agree? You can <Link to="/account">delete your account</Link> instead.
        </p>
      </div>
    </AuthFrame>
  )
}
