import { formatBytes } from '../lib/format.ts'
import type { Attachment } from '../types/api.ts'

/** Download links for a ticket's files. The link itself carries the session cookie; the server forces a download. */
export function AttachmentList({ attachments, hrefFor }: { attachments: Attachment[]; hrefFor: (attachment: Attachment) => string }) {
  if (attachments.length === 0) return null
  return (
    <ul className="attachments" aria-label="Attachments">
      {attachments.map((attachment) => (
        <li key={attachment.id}>
          <a href={hrefFor(attachment)} download={attachment.fileName}>
            {attachment.fileName}
          </a>{' '}
          <span className="muted small">({formatBytes(attachment.sizeBytes)})</span>
        </li>
      ))}
    </ul>
  )
}
