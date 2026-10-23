import { useRef, useState, type FormEvent } from 'react'
import { api, toApiError } from '../../lib/api.ts'
import { problemWithFile, uploadAll } from '../../lib/attachments.ts'
import type { TicketStatus } from '../../types/api.ts'
import { useConfig } from '../config/useConfig.ts'

/** Adds files to an existing ticket. Hidden once the ticket is closed or has its maximum number of files. */
export function AttachFiles({
  ticketId,
  status,
  attachedCount,
  onDone,
}: {
  ticketId: string
  status: TicketStatus
  attachedCount: number
  onDone: () => void
}) {
  const { attachments: limits } = useConfig()
  const input = useRef<HTMLInputElement>(null)
  const [files, setFiles] = useState<File[]>([])
  const [problems, setProblems] = useState<string[]>([])
  const [uploading, setUploading] = useState(false)

  if (status === 'CLOSED' || attachedCount >= limits.maxFilesPerTicket) return null
  const room = limits.maxFilesPerTicket - attachedCount

  const choose = (chosen: File[]) => {
    const messages: string[] = []
    const accepted: File[] = []
    chosen.forEach((file) => {
      const problem = problemWithFile(file, limits, attachedCount + accepted.length)
      if (problem) messages.push(problem)
      else accepted.push(file)
    })
    setFiles(accepted)
    setProblems(messages)
  }

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (files.length === 0) return
    setUploading(true)
    const failures = await uploadAll(
      files,
      (file) => api.uploadAttachment(ticketId, file),
      (error) => toApiError(error).message,
    )
    setUploading(false)
    setProblems(failures)
    setFiles([])
    if (input.current) input.current.value = ''
    onDone()
  }

  return (
    <form className="card" onSubmit={handleSubmit} aria-labelledby="attach-heading">
      <h2 id="attach-heading">Attach files</h2>
      <div className="field">
        <label htmlFor="attach-input" className="visually-hidden">
          Choose files to attach
        </label>
        <input
          id="attach-input"
          ref={input}
          type="file"
          multiple
          accept={limits.allowedTypes.join(',')}
          onChange={(event) => choose(Array.from(event.target.files ?? []))}
          aria-describedby="attach-hint"
        />
        <p id="attach-hint" className="muted small">
          You can add {room} more. Images (JPEG, PNG, WebP) and PDF, up to {Math.round(limits.maxFileBytes / 1024 / 1024)} MB each.
        </p>
      </div>
      {problems.length > 0 && (
        <div className="banner banner-error" role="alert">
          {problems.map((problem) => (
            <p key={problem}>{problem}</p>
          ))}
        </div>
      )}
      <button type="submit" className="button" disabled={uploading || files.length === 0}>
        {uploading ? 'Uploading…' : files.length > 1 ? `Upload ${files.length} files` : 'Upload'}
      </button>
    </form>
  )
}
