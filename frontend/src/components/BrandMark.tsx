/** The game's mark: one province parcel with its city, drawn like a survey plot. */
export function BrandMark({ className = 'brand__mark' }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path
        d="M5.2 6.6 11.4 2.8l7.4 2.4 2.6 6.6-3.7 7.4-7.6 2.1-6-5.1z"
        style={{ fill: 'rgb(217 101 59 / 0.22)', stroke: 'var(--accent)' }}
        strokeWidth="1.6"
        strokeLinejoin="round"
      />
      <circle cx="12.4" cy="11.6" r="2.3" style={{ fill: 'var(--accent-strong)' }} />
    </svg>
  )
}
