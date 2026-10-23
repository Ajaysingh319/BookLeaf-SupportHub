import { describe, expect, it } from 'vitest'
import { createFormatters, firstName, formatBytes, formatAge, isInProduction, labelFor, parseDateOnly } from './format.ts'

// Locale and currency are inputs, as they are in the app (they come from the server's config).
const indian = createFormatters({ locale: 'en-IN', currencyCode: 'INR' })
const american = createFormatters({ locale: 'en-US', currencyCode: 'USD' })

describe('money', () => {
  it('shows zero as a real value, not as missing', () => {
    expect(indian.money(0)).toBe('₹0')
  })

  it('shows a missing value as a dash', () => {
    expect(indian.money(null)).toBe('—')
  })

  it('follows the configured locale and currency', () => {
    expect(indian.money(1234567)).toBe('₹12,34,567')
    expect(american.money(1234567)).toBe('$1,234,567')
  })
})

describe('count', () => {
  it('keeps zero and marks null', () => {
    expect(indian.count(0)).toBe('0')
    expect(indian.count(null)).toBe('—')
  })
})

describe('dates', () => {
  it('parses a date-only string as the same local calendar day', () => {
    const date = parseDateOnly('2024-01-10')
    expect([date.getFullYear(), date.getMonth(), date.getDate()]).toEqual([2024, 0, 10])
  })

  it('formats a date and a missing date', () => {
    expect(indian.date('2024-01-10')).toMatch(/10/)
    expect(indian.date('2024-01-10')).toMatch(/2024/)
    expect(indian.date(null)).toBe('—')
  })
})

describe('isInProduction', () => {
  it('uses the prefix it is given, not a built-in one', () => {
    expect(isInProduction('In Production - Cover Design', 'In Production')).toBe(true)
    expect(isInProduction('Published & Live', 'In Production')).toBe(false)
    expect(isInProduction('Drafting - Layout', 'Drafting')).toBe(true)
  })
})

describe('firstName', () => {
  it('takes the first word', () => {
    expect(firstName('Priya Sharma')).toBe('Priya')
    expect(firstName('  Farhan   Sheikh ')).toBe('Farhan')
    expect(firstName('')).toBe('')
  })
})

describe('labelFor', () => {
  const options = [
    { value: 'IN_PROGRESS', label: 'In Progress' },
    { value: 'OPEN', label: 'Open' },
  ]

  it('uses the label the server provided', () => {
    expect(labelFor(options, 'IN_PROGRESS')).toBe('In Progress')
  })

  it('falls back to the raw value for an unknown option and a dash for null', () => {
    expect(labelFor(options, 'BRAND_NEW')).toBe('BRAND_NEW')
    expect(labelFor(options, null)).toBe('—')
  })
})

describe('formatAge', () => {
  it('shows short, hourly and day-based ages', () => {
    expect(formatAge(0)).toBe('under 1 h')
    expect(formatAge(5)).toBe('5 h')
    expect(formatAge(47)).toBe('47 h')
    expect(formatAge(52)).toBe('2 d 4 h')
  })
})

describe('formatBytes', () => {
  it('shows bytes, KB and MB in a short readable form', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1023)).toBe('1023 B')
    expect(formatBytes(1024)).toBe('1 KB')
    expect(formatBytes(1536)).toBe('1.5 KB')
    expect(formatBytes(5 * 1024 * 1024)).toBe('5 MB')
    expect(formatBytes(1.25 * 1024 * 1024)).toBe('1.3 MB')
  })
})
