import { Link } from 'react-router'
import { useAuth } from '../auth/AuthContext'
import { legalDocuments, type LegalDocumentKey } from '../legal/documents'
import { Markdown } from '../legal/Markdown'

/** A public legal page (no sign-in needed), linked from the sign-in, sign-up and account pages. */
export function LegalPage({ document }: { document: LegalDocumentKey }) {
  const { session } = useAuth()
  const doc = legalDocuments[document]
  return (
    <div className="legal">
      <nav className="legal__nav" aria-label="Legal documents">
        <Link to={session ? '/' : '/login'} className="brand">
          <span className="brand__mark" aria-hidden="true">⚜</span>
          <span className="brand__name">Blaspi</span>
        </Link>
        <LegalLinks />
      </nav>
      <article className="legal__body">
        <Markdown source={doc.source} />
      </article>
    </div>
  )
}

/** Links to the three legal documents, for page footers. */
export function LegalLinks() {
  return (
    <span className="legal-links">
      {Object.values(legalDocuments).map((doc) => (
        <Link key={doc.path} to={doc.path}>
          {doc.title}
        </Link>
      ))}
    </span>
  )
}
