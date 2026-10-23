import type { ClientConfig } from '../types/api.ts'
import { formatBytes } from './format.ts'

/**
 * Quick checks before a file is sent, using the limits the server publishes. The server still checks the file's
 * real content; this only saves a round trip for the obvious mistakes. Returns a message, or null when fine.
 */
export function problemWithFile(file: File, limits: ClientConfig['attachments'], alreadyAttached: number): string | null {
  if (alreadyAttached >= limits.maxFilesPerTicket) {
    return `A ticket can have at most ${limits.maxFilesPerTicket} attachments.`
  }
  if (file.size === 0) return `${file.name} is empty.`
  if (file.size > limits.maxFileBytes) {
    return `${file.name} is larger than ${formatBytes(limits.maxFileBytes)}.`
  }
  if (file.type && !limits.allowedTypes.includes(file.type)) {
    return `${file.name}: this type of file is not allowed.`
  }
  return null
}

/** Uploads files one after another; returns a message for each file that failed. */
export async function uploadAll(
  files: File[],
  upload: (file: File) => Promise<unknown>,
  describeError: (error: unknown) => string,
): Promise<string[]> {
  const failures: string[] = []
  for (const file of files) {
    try {
      await upload(file)
    } catch (error) {
      failures.push(`${file.name}: ${describeError(error)}`)
    }
  }
  return failures
}
