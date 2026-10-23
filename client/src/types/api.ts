// Shapes returned by the BookLeaf API (/api/v1). Dates are ISO strings; amounts are rupees.

export type Role = 'AUTHOR' | 'ADMIN'

export interface UserSummary {
  id: string
  name: string
  email: string
  role: Role
}

export interface LoginResponse {
  token: string
  user: UserSummary
}

/** Null amounts/dates mean "not available yet" (a book still in production); zero is a real value. */
export interface Book {
  id: string
  sourceBookId: string
  title: string
  isbn: string
  genre: string
  publicationDate: string | null
  status: string
  mrp: number | null
  totalCopiesSold: number | null
  totalRoyaltyEarned: number | null
  royaltyPaid: number | null
  royaltyPending: number | null
  lastRoyaltyPayoutDate: string | null
  printPartner: string | null
  availableOn: string[]
}

export type TicketStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED'

export interface TicketSummary {
  id: string
  subject: string
  status: TicketStatus
  bookId: string | null
  bookTitle: string | null
  createdAt: string
  updatedAt: string
}

export interface TicketMessage {
  id: string
  body: string
  senderName: string
  senderRole: Role
  createdAt: string
}

export interface TicketDetail {
  id: string
  subject: string
  description: string
  status: TicketStatus
  bookId: string | null
  bookTitle: string | null
  createdAt: string
  updatedAt: string
  messages: TicketMessage[]
}

export interface Page<T> {
  data: T[]
  pagination: { page: number; size: number; totalItems: number; totalPages: number }
}

export interface CreateTicketInput {
  /** null means "General / Account Level". */
  bookId: string | null
  subject: string
  description: string
}

export interface FieldDetail {
  field: string
  message: string
}

export interface Option {
  value: string
  label: string
}

/** allowedNext: the statuses a ticket in this status may be moved to (the server enforces the same table). */
export interface StatusOption extends Option {
  allowedNext: string[]
}

/** Settings the server sends at startup (GET /api/v1/config). The client keeps no copies of them. */
export interface ClientConfig {
  brandName: string
  /** ISO 4217 code (for example EUR); the browser turns it into the right symbol for the locale. */
  currencyCode: string
  locale: string
  ticketPollMs: number
  listPollMs: number
  defaultPageSize: number
  inProductionStatusPrefix: string
  dashboardRecentTickets: number
  staleTicketHours: number
  policy: { royaltyPayoutDays: number; minimumPayout: number }
  validation: {
    subjectMin: number
    subjectMax: number
    descriptionMin: number
    descriptionMax: number
    /** Longest reply or internal note. */
    messageMax: number
  }
  options: { statuses: StatusOption[]; categories: Option[]; priorities: Option[] }
}

// ---- Admin ----

export type AiOutcome = 'PENDING' | 'SUCCESS' | 'FALLBACK'
export type MessageVisibility = 'PUBLIC' | 'INTERNAL'

/** One row of the admin queue. Category and priority are the final values (values come from config options). */
export interface AdminTicketSummary {
  id: string
  subject: string
  status: TicketStatus
  category: string
  priority: string
  needsManualReview: boolean
  authorId: string
  authorName: string
  bookId: string | null
  bookTitle: string | null
  assignedAdminId: string | null
  assignedAdminName: string | null
  createdAt: string
  updatedAt: string
  ageHours: number
}

export interface AdminMessage {
  id: string
  body: string
  senderName: string
  senderRole: Role
  visibility: MessageVisibility
  createdAt: string
}

export interface AdminTicketDetail {
  summary: AdminTicketSummary
  description: string
  resolvedAt: string | null
  /** When the latest public reply was sent to the author. */
  repliedAt: string | null
  author: { id: string; name: string; email: string; city: string | null }
  book: Book | null
  ai: {
    outcome: AiOutcome
    suggestedCategory: string | null
    suggestedPriority: string | null
    reason: string | null
  }
  messages: AdminMessage[]
}

/** Only the fields that are present are changed. */
export interface AdminTicketPatch {
  status?: string
  category?: string
  priority?: string
  assignedAdminId?: string
}

export interface QueueStats {
  byStatus: Record<string, number>
  unresolvedByPriority: Record<string, number>
  unassignedUnresolved: number
}

export interface QueueFilters {
  status?: string
  category?: string
  priority?: string
  from?: string
  to?: string
}

/** The common error shape: { error: { code, message, details? }, requestId }. */
export interface ApiErrorBody {
  error: { code: string; message: string; details?: FieldDetail[] }
  requestId?: string
}
