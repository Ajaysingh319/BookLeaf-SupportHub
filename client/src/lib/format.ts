/** Display formatting. Locale and currency come from the server's config, never from this file. */
export interface Formatters {
  /** Zero is shown as a real amount; only a missing value (null) is shown as "—". */
  money: (value: number | null) => string
  count: (value: number | null) => string
  date: (value: string | null) => string
  dateTime: (isoTimestamp: string) => string
  time: (date: Date) => string
}

export function createFormatters(settings: { locale: string; currencyCode: string }): Formatters {
  const numbers = new Intl.NumberFormat(settings.locale, { maximumFractionDigits: 2 })
  const currency = new Intl.NumberFormat(settings.locale, {
    style: 'currency',
    currency: settings.currencyCode,
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  })
  const dateFormat = new Intl.DateTimeFormat(settings.locale, { day: 'numeric', month: 'short', year: 'numeric' })
  const dateTimeFormat = new Intl.DateTimeFormat(settings.locale, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  })
  const timeFormat = new Intl.DateTimeFormat(settings.locale, { hour: 'numeric', minute: '2-digit', second: '2-digit' })

  return {
    money: (value) => (value === null ? '—' : currency.format(value)),
    count: (value) => (value === null ? '—' : numbers.format(value)),
    date: (value) => (value === null ? '—' : dateFormat.format(parseDateOnly(value))),
    dateTime: (isoTimestamp) => dateTimeFormat.format(new Date(isoTimestamp)),
    time: (date) => timeFormat.format(date),
  }
}

/** Parses a date-only string (2024-01-10) as a local date, so it never shifts by a time zone. */
/** 1536 -> "1.5 KB". Sizes are shown for people, so binary units are rounded to one decimal at most. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${Number.isInteger(value) ? value : value.toFixed(1)} ${units[unit]}`
}

export function parseDateOnly(value: string): Date {
  const [year = 0, month = 1, day = 1] = value.split('-').map(Number)
  return new Date(year, month - 1, day)
}

/** How long a ticket has waited, for the admin queue: "under 1 h", "5 h", "2 d 4 h". */
export function formatAge(hours: number): string {
  if (hours < 1) return 'under 1 h'
  if (hours < 48) return `${hours} h`
  return `${Math.floor(hours / 24)} d ${hours % 24} h`
}

/** Looks up the display label for a value from the server-provided options, falling back to the value itself. */
export function labelFor(options: { value: string; label: string }[], value: string | null): string {
  if (value === null) return '—'
  return options.find((option) => option.value === value)?.label ?? value
}

export function firstName(fullName: string): string {
  return fullName.trim().split(/\s+/)[0] ?? ''
}

/** A book is still in production when its status starts with the server-configured prefix. */
export function isInProduction(status: string, prefix: string): boolean {
  return status.toLowerCase().startsWith(prefix.toLowerCase())
}
