import { Fragment, type ReactNode } from 'react'
import { Link } from 'react-router'

/**
 * Renders the small Markdown subset of the legal drafts (headings, paragraphs, lists, tables, bold,
 * inline code, links) as React elements. No HTML is ever injected, so the texts cannot carry markup;
 * `[OPERATOR: …]` placeholders are highlighted so an unfinished draft is obvious on screen.
 */
export function Markdown({ source }: { source: string }) {
  return <>{parseBlocks(source)}</>
}

function parseBlocks(source: string): ReactNode[] {
  const lines = source.replace(/\r\n/g, '\n').split('\n')
  const blocks: ReactNode[] = []
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    const heading = /^(#{1,3}) (.*)$/.exec(line)
    if (line.trim() === '') {
      i++
    } else if (heading) {
      const level = heading[1].length
      const content = inline(heading[2])
      blocks.push(level === 1 ? <h1 key={i}>{content}</h1> : level === 2 ? <h2 key={i}>{content}</h2> : <h3 key={i}>{content}</h3>)
      i++
    } else if (line.startsWith('- ')) {
      const items: string[] = []
      while (i < lines.length && lines[i].startsWith('- ')) items.push(lines[i++].slice(2))
      blocks.push(
        <ul key={i}>
          {items.map((item, n) => (
            <li key={n}>{inline(item)}</li>
          ))}
        </ul>,
      )
    } else if (line.startsWith('|')) {
      const rows: string[][] = []
      while (i < lines.length && lines[i].startsWith('|')) {
        const cells = lines[i++].split('|').slice(1, -1).map((cell) => cell.trim())
        if (!cells.every((cell) => /^-+$/.test(cell))) rows.push(cells)
      }
      const [head, ...body] = rows
      blocks.push(
        <div key={i} className="legal__table">
          <table>
            <thead>
              <tr>{head.map((cell, n) => <th key={n}>{inline(cell)}</th>)}</tr>
            </thead>
            <tbody>
              {body.map((row, r) => (
                <tr key={r}>{row.map((cell, n) => <td key={n}>{inline(cell)}</td>)}</tr>
              ))}
            </tbody>
          </table>
        </div>,
      )
    } else {
      const paragraph: string[] = []
      while (i < lines.length && lines[i].trim() !== '' && !/^(#|- |\|)/.test(lines[i])) paragraph.push(lines[i++])
      blocks.push(<p key={i}>{inline(paragraph.join(' '))}</p>)
    }
  }
  return blocks
}

const INLINE = /(\*\*[^*]+\*\*|`[^`]+`|\[[^\]]+\]\([^)\s]+\))/g

function inline(text: string): ReactNode {
  return text.split(INLINE).map((part, n) => {
    if (part.startsWith('**') && part.endsWith('**')) return <strong key={n}>{part.slice(2, -2)}</strong>
    if (part.startsWith('`') && part.endsWith('`')) {
      const code = part.slice(1, -1)
      return code.startsWith('[OPERATOR') ? (
        <mark key={n} className="legal__placeholder">
          {code}
        </mark>
      ) : (
        <code key={n}>{code}</code>
      )
    }
    const link = /^\[([^\]]+)\]\(([^)\s]+)\)$/.exec(part)
    if (link) {
      const [, label, href] = link
      // Only the app's own pages and https links; anything else is shown as plain text.
      if (href.startsWith('/')) return <Link key={n} to={href}>{label}</Link>
      if (href.startsWith('https://')) {
        return (
          <a key={n} href={href} target="_blank" rel="noopener noreferrer">
            {label}
          </a>
        )
      }
      return <Fragment key={n}>{label}</Fragment>
    }
    return <Fragment key={n}>{part}</Fragment>
  })
}
