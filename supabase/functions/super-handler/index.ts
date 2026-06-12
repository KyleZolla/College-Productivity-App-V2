type EstimateFeedbackRecord = {
  course_name?: string | null
  course_level?: string | null
  task_title?: string | null
  step_title?: string | null
  feedback?: 'too_short' | 'about_right' | 'too_long'
  estimated_hours?: number | null
  was_completed_late?: boolean | null
  completed_at?: string | null
  created_at?: string | null
}

const AI_EVENT_TYPE_COMPLEX_TASK_CREATION = 'complex_task_creation'
const COMPLEX_TASK_LIMIT = 5
const COMPLEX_TASK_WINDOW_DAYS = 7

// --- Input size limits ---------------------------------------------------
// The Android client extracts at most 120k chars from a document and 12k from
// a photo, and caps typed fields well below these values, so legitimate users
// never hit them. They exist to stop direct API callers from sending
// maximally expensive payloads to OpenAI.
const MAX_BODY_CHARS = 500_000
const STRING_LIMITS: Record<string, number> = {
  title: 300,
  dueDate: 100,
  assignmentType: 100,
  difficulty: 50,
  requirements: 8_000,
  documentContent: 150_000,
  photoText: 20_000,
  courseName: 200,
  courseLevel: 50,
  courseProfile: 10_000,
  school: 200,
  yearInSchool: 50,
  timeZone: 100,
}
const MAX_FEEDBACK_RECORDS = 50
const MAX_FEEDBACK_FIELD_CHARS = 300
const MAX_WORKLOAD_ENTRIES = 120
const MAX_USER_ESTIMATED_HOURS = 500

function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function inputTooLargeResponse(field: string, max: number): Response {
  return jsonResponse(
    {
      error: 'INPUT_TOO_LARGE',
      message: `${field} is too long (max ${max} characters).`,
    },
    400,
  )
}

/** Returns an error Response when any capped string field exceeds its limit. */
function checkStringLimits(body: Record<string, unknown>): Response | null {
  for (const [field, max] of Object.entries(STRING_LIMITS)) {
    const value = body[field]
    if (typeof value === 'string' && value.length > max) {
      return inputTooLargeResponse(field, max)
    }
  }
  return null
}

/** Caps the history list length and the size of every string field in it. */
function sanitizeFeedbackHistory(history: unknown): EstimateFeedbackRecord[] {
  if (!Array.isArray(history)) return []
  return history.slice(0, MAX_FEEDBACK_RECORDS).map((record) => {
    if (typeof record !== 'object' || record === null) return {}
    const out: Record<string, unknown> = {}
    for (const [key, value] of Object.entries(record)) {
      out[key] = typeof value === 'string' ? value.slice(0, MAX_FEEDBACK_FIELD_CHARS) : value
    }
    return out as EstimateFeedbackRecord
  })
}

/** Keeps at most MAX_WORKLOAD_ENTRIES sane date->hours pairs. */
function sanitizeExistingWorkload(workload: unknown): Record<string, number> {
  if (typeof workload !== 'object' || workload === null || Array.isArray(workload)) return {}
  const out: Record<string, number> = {}
  let kept = 0
  for (const [key, value] of Object.entries(workload)) {
    if (kept >= MAX_WORKLOAD_ENTRIES) break
    const hours = typeof value === 'number' ? value : Number(value)
    if (!/^\d{4}-\d{2}-\d{2}$/.test(key)) continue
    if (!Number.isFinite(hours) || hours < 0 || hours > 24) continue
    out[key] = hours
    kept++
  }
  return out
}

/** Returns a sane positive estimate, or null when missing/invalid. */
function sanitizeUserEstimatedHours(value: unknown): number | null {
  const hours = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(hours) || hours <= 0 || hours > MAX_USER_ESTIMATED_HOURS) return null
  return hours
}
// -------------------------------------------------------------------------

function formatFeedbackLabel(feedback?: 'too_short' | 'about_right' | 'too_long'): string {
  if (feedback === 'too_short') return 'too short'
  if (feedback === 'about_right') return 'about right'
  if (feedback === 'too_long') return 'too long'
  return 'unknown'
}

function normalizeCourseName(courseName?: string | null): string {
  return (courseName || '').trim().toLowerCase()
}

function filterRelevantEstimateFeedbackHistory(
  estimateFeedbackHistory?: EstimateFeedbackRecord[],
  currentCourseName?: string | null,
): EstimateFeedbackRecord[] {
  if (!estimateFeedbackHistory) return []

  const normalizedCurrentCourseName = normalizeCourseName(currentCourseName)

  if (normalizedCurrentCourseName) {
    return estimateFeedbackHistory.filter((record) =>
      normalizeCourseName(record.course_name) === normalizedCurrentCourseName
    )
  }

  return estimateFeedbackHistory.filter((record) =>
    !normalizeCourseName(record.course_name)
  )
}

function buildPersonalPlanningSummary(
  estimateFeedbackHistory?: EstimateFeedbackRecord[],
  currentCourseName?: string | null,
): string {
  const relevantHistory = filterRelevantEstimateFeedbackHistory(
    estimateFeedbackHistory,
    currentCourseName,
  )

  if (relevantHistory.length < 3) {
    return 'The user has limited relevant time-estimate feedback history so far. Rely mostly on the current task details.'
  }

  const counts = {
    too_short: 0,
    about_right: 0,
    too_long: 0,
  }

  for (const record of relevantHistory) {
    if (record.feedback === 'too_short') counts.too_short++
    if (record.feedback === 'about_right') counts.about_right++
    if (record.feedback === 'too_long') counts.too_long++
  }

  const currentTaskHasCourse = Boolean(normalizeCourseName(currentCourseName))

  const examples = relevantHistory
    .filter((record) => record.feedback && record.step_title)
    .slice(0, 8)
    .map((record) => {
      const courseName = record.course_name || 'Unknown course'
      const courseLevel = record.course_level ? `, ${record.course_level} level` : ''
      const taskTitle = record.task_title || 'Unknown task'
      const stepTitle = record.step_title || 'Unknown step'
      const estimatedHours =
        typeof record.estimated_hours === 'number'
          ? `${record.estimated_hours} hours`
          : 'unknown time'
      const feedbackLabel = formatFeedbackLabel(record.feedback)
      const completedLate = record.was_completed_late ? 'yes' : 'no'

      if (currentTaskHasCourse) {
        return `- Course: "${courseName}${courseLevel}". Task: "${taskTitle}". Step: "${stepTitle}". Estimated: ${estimatedHours}. Feedback: ${feedbackLabel}. Completed late: ${completedLate}.`
      }

      return `- Task: "${taskTitle}". Step: "${stepTitle}". Estimated: ${estimatedHours}. Feedback: ${feedbackLabel}. Completed late: ${completedLate}.`
    })
    .join('\n')

  const scopeText = currentTaskHasCourse
    ? `Only feedback from "${currentCourseName}" is included.`
    : 'No course was specified, so only feedback from no-course tasks is included.'

  return `Relevant planning history:
${scopeText}
Total feedback: ${relevantHistory.length}
Overall: ${counts.too_short} too short, ${counts.about_right} about right, ${counts.too_long} too long.

Recent examples:
${examples || 'No usable recent examples.'}

Use similar past examples as a soft signal for time estimates and scheduling. Do not broadly adjust everything from totals alone. If similar steps were completed late, consider more buffer, but do not automatically increase the estimate.`
}

function isValidTimeZone(timeZone: unknown): timeZone is string {
  if (typeof timeZone !== 'string' || !timeZone.trim()) {
    return false
  }

  try {
    new Intl.DateTimeFormat('en-US', { timeZone })
    return true
  } catch {
    return false
  }
}

function formatDateInTimeZone(date: Date, timeZone: string): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date)

  const year = parts.find((part) => part.type === 'year')?.value
  const month = parts.find((part) => part.type === 'month')?.value
  const day = parts.find((part) => part.type === 'day')?.value

  return `${year}-${month}-${day}`
}

function getHourInTimeZone(date: Date, timeZone: string): number {
  const hourText = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hour: '2-digit',
    hour12: false,
    hourCycle: 'h23',
  }).format(date)

  return Number(hourText)
}

function getTimeInTimeZone(date: Date, timeZone: string): string {
  return date.toLocaleTimeString('en-US', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: true,
    timeZone,
  })
}

function addDaysToDateString(dateString: string, days: number): string {
  const [year, month, day] = dateString.split('-').map(Number)
  const date = new Date(Date.UTC(year, month - 1, day))
  date.setUTCDate(date.getUTCDate() + days)

  const newYear = date.getUTCFullYear()
  const newMonth = String(date.getUTCMonth() + 1).padStart(2, '0')
  const newDay = String(date.getUTCDate()).padStart(2, '0')

  return `${newYear}-${newMonth}-${newDay}`
}

function clampRoadmapDatesToAllowedRange(
  result: any,
  startDate: string,
  effectiveDeadline: string,
): any {
  if (!result?.steps || !Array.isArray(result.steps)) {
    return result
  }

  return {
    ...result,
    steps: result.steps.map((step: any) => {
      let recommendedDate = step.recommendedDate

      if (!recommendedDate || recommendedDate < startDate) {
        recommendedDate = startDate
      }

      if (recommendedDate > effectiveDeadline) {
        recommendedDate = effectiveDeadline
      }

      return {
        ...step,
        recommendedDate,
      }
    }),
  }
}

function getSupabaseEnv() {
  const supabaseUrl = Deno.env.get('SUPABASE_URL')
  const serviceRoleKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')

  if (!supabaseUrl || !serviceRoleKey) {
    throw new Error('Missing SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY')
  }

  return { supabaseUrl, serviceRoleKey }
}

function getBearerToken(req: Request): string | null {
  const authHeader = req.headers.get('Authorization') || ''

  if (!authHeader.startsWith('Bearer ')) {
    return null
  }

  return authHeader.replace('Bearer ', '').trim()
}

async function getUserIdFromAccessToken(accessToken: string): Promise<string> {
  const { supabaseUrl, serviceRoleKey } = getSupabaseEnv()

  const response = await fetch(`${supabaseUrl}/auth/v1/user`, {
    method: 'GET',
    headers: {
      Authorization: `Bearer ${accessToken}`,
      apikey: serviceRoleKey,
    },
  })

  if (!response.ok) {
    throw new Error('Unable to authenticate user')
  }

  const user = await response.json()

  if (!user?.id) {
    throw new Error('Authenticated user id missing')
  }

  return user.id
}

async function countAiUsageEvents(
  userId: string,
  eventType: string,
  windowDays: number,
): Promise<number> {
  const { supabaseUrl, serviceRoleKey } = getSupabaseEnv()

  const cutoff = new Date(Date.now() - windowDays * 24 * 60 * 60 * 1000).toISOString()

  const url =
    `${supabaseUrl}/rest/v1/ai_usage_events` +
    `?user_id=eq.${encodeURIComponent(userId)}` +
    `&event_type=eq.${encodeURIComponent(eventType)}` +
    `&created_at=gte.${encodeURIComponent(cutoff)}` +
    `&select=id`

  const response = await fetch(url, {
    method: 'GET',
    headers: {
      apikey: serviceRoleKey,
      Authorization: `Bearer ${serviceRoleKey}`,
      Prefer: 'count=exact',
      Range: '0-0',
    },
  })

  if (!response.ok) {
    throw new Error('Unable to count AI usage events')
  }

  const contentRange = response.headers.get('content-range')
  const countText = contentRange?.split('/')[1]
  const count = countText ? Number(countText) : 0

  return Number.isFinite(count) ? count : 0
}

async function createAiUsageEvent(params: {
  userId: string
  eventType: string
  taskId?: number | null
  courseId?: number | null
}): Promise<number | null> {
  const { supabaseUrl, serviceRoleKey } = getSupabaseEnv()

  const response = await fetch(`${supabaseUrl}/rest/v1/ai_usage_events`, {
    method: 'POST',
    headers: {
      apikey: serviceRoleKey,
      Authorization: `Bearer ${serviceRoleKey}`,
      'Content-Type': 'application/json',
      Prefer: 'return=representation',
    },
    body: JSON.stringify({
      user_id: params.userId,
      event_type: params.eventType,
      task_id: params.taskId ?? null,
      course_id: params.courseId ?? null,
      success: false,
    }),
  })

  if (!response.ok) {
    const errorText = await response.text()
    throw new Error(`Unable to create AI usage event: ${errorText}`)
  }

  const rows = await response.json()
  return rows?.[0]?.id ?? null
}

async function updateAiUsageEvent(params: {
  usageEventId: number | null
  success: boolean
  errorMessage?: string | null
}) {
  if (!params.usageEventId) {
    return
  }

  const { supabaseUrl, serviceRoleKey } = getSupabaseEnv()

  await fetch(`${supabaseUrl}/rest/v1/ai_usage_events?id=eq.${params.usageEventId}`, {
    method: 'PATCH',
    headers: {
      apikey: serviceRoleKey,
      Authorization: `Bearer ${serviceRoleKey}`,
      'Content-Type': 'application/json',
      Prefer: 'return=minimal',
    },
    body: JSON.stringify({
      success: params.success,
      error_message: params.errorMessage?.slice(0, 500) ?? null,
    }),
  })
}

function asNullableNumber(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }

  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : null
  }

  return null
}

Deno.serve(async (req) => {
  let usageEventId: number | null = null

  try {
    const accessToken = getBearerToken(req)

    if (!accessToken) {
      return jsonResponse(
        {
          error: 'UNAUTHORIZED',
          message: 'You must be signed in to generate a roadmap.',
        },
        401,
      )
    }

    let userId: string

    try {
      userId = await getUserIdFromAccessToken(accessToken)
    } catch (error) {
      console.error('Auth error:', error)

      return jsonResponse(
        {
          error: 'UNAUTHORIZED',
          message: 'You must be signed in to generate a roadmap.',
        },
        401,
      )
    }

    const usageCount = await countAiUsageEvents(
      userId,
      AI_EVENT_TYPE_COMPLEX_TASK_CREATION,
      COMPLEX_TASK_WINDOW_DAYS,
    )

    console.log('Complex task AI usage count:', usageCount)

    if (usageCount >= COMPLEX_TASK_LIMIT) {
      return jsonResponse(
        {
          error: 'AI_LIMIT_REACHED',
          message: 'You’ve used your 5 free AI roadmaps in the last 7 days. You can still create simple tasks.',
        },
        429,
      )
    }

    // Cap the raw body before parsing so oversized payloads are rejected early.
    const rawBody = await req.text()
    if (rawBody.length > MAX_BODY_CHARS) {
      return inputTooLargeResponse('request body', MAX_BODY_CHARS)
    }

    let body: Record<string, unknown>
    try {
      body = JSON.parse(rawBody)
    } catch {
      return jsonResponse({ error: 'INVALID_JSON', message: 'Request body is not valid JSON.' }, 400)
    }

    const limitError = checkStringLimits(body)
    if (limitError) {
      return limitError
    }

    const {
      title,
      dueDate,
      assignmentType,
      difficulty,
      requirements,
      documentContent,
      photoText,
      courseName,
      courseLevel,
      courseProfile,
      school,
      yearInSchool,
      timeZone,
      taskId,
      courseId,
    } = body as Record<string, any>

    const estimateFeedbackHistory = sanitizeFeedbackHistory(body.estimateFeedbackHistory)
    const existingWorkload = sanitizeExistingWorkload(body.existingWorkload)
    const userEstimatedHours = sanitizeUserEstimatedHours(body.userEstimatedHours)

    usageEventId = await createAiUsageEvent({
      userId,
      eventType: AI_EVENT_TYPE_COMPLEX_TASK_CREATION,
      taskId: asNullableNumber(taskId),
      courseId: asNullableNumber(courseId),
    })

    console.log('Raw existingWorkload received:', JSON.stringify(existingWorkload))
    console.log('All received fields:', JSON.stringify({
      hasTitle: !!title,
      hasDueDate: !!dueDate,
      hasAssignmentType: !!assignmentType,
      hasDifficulty: !!difficulty,
      hasRequirements: !!requirements,
      hasDocumentContent: !!documentContent,
      hasPhotoText: !!photoText,
      hasExistingWorkload: !!existingWorkload,
      existingWorkloadKeys: existingWorkload ? Object.keys(existingWorkload) : [],
      hasCourseName: !!courseName,
      hasCourseLevel: !!courseLevel,
      hasCourseProfile: !!courseProfile,
      hasUserEstimatedHours: !!userEstimatedHours,
      hasSchool: !!school,
      hasYearInSchool: !!yearInSchool,
      estimateFeedbackHistoryCount: estimateFeedbackHistory?.length ?? 0,
      hasTimeZone: !!timeZone,
      taskId,
      courseId,
    }))

    const userTimeZone = isValidTimeZone(timeZone) ? timeZone : 'UTC'

    const now = new Date()
    const today = formatDateInTimeZone(now, userTimeZone)
    const currentHour = getHourInTimeZone(now, userTimeZone)
    const currentTime = getTimeInTimeZone(now, userTimeZone)
    const hoursLeftToday = Math.max(0, 23 - currentHour)

    const startDate = hoursLeftToday < 2
      ? addDaysToDateString(today, 1)
      : today

    const dueDateObj = new Date(dueDate)
    const dueTime = getTimeInTimeZone(dueDateObj, userTimeZone)
    const dueDateOnly = formatDateInTimeZone(dueDateObj, userTimeZone)
    const dueHour = getHourInTimeZone(dueDateObj, userTimeZone)

    const calculatedEffectiveDeadline = dueHour < 12
      ? addDaysToDateString(dueDateOnly, -1)
      : dueDateOnly

    const effectiveDeadline = calculatedEffectiveDeadline < startDate
      ? startDate
      : calculatedEffectiveDeadline

    const allDates: string[] = []
    let cursor = startDate

    while (cursor <= effectiveDeadline) {
      allDates.push(cursor)
      cursor = addDaysToDateString(cursor, 1)
    }

    const workloadSummary = allDates
      .map((date) => {
        const hours = (existingWorkload || {})[date]
        return hours ? `- ${date}: ${hours} hours already scheduled` : `- ${date}: free`
      })
      .join('\n')

    console.log('Workload summary sent to model:\n', workloadSummary)

    const personalPlanningSummary = buildPersonalPlanningSummary(
      estimateFeedbackHistory,
      courseName,
    )

    console.log('Received:', JSON.stringify({
      title,
      dueDate,
      assignmentType,
      difficulty,
      requirements,
      courseName,
      courseLevel,
      userEstimatedHours,
      school,
      yearInSchool,
      timeZone: userTimeZone,
    }))
    console.log('Today:', today)
    console.log('Current time:', currentTime)
    console.log('Start date:', startDate)
    console.log('Due date only:', dueDateOnly)
    console.log('Due time:', dueTime)
    console.log('Due hour:', dueHour)
    console.log('Calculated effective deadline:', calculatedEffectiveDeadline)
    console.log('Effective deadline used:', effectiveDeadline)
    console.log('Existing workload:', JSON.stringify(existingWorkload))
    console.log('Using course profile:', Boolean(courseProfile))
    console.log('Course profile length:', courseProfile?.length || 0)
    console.log('Personal planning summary:', personalPlanningSummary)

    const prompt = `
You are an academic planning assistant. Create a realistic roadmap for a college assignment.

Student/task context:
- User timezone: ${userTimeZone}
- Today: ${today}
- Current time: ${currentTime}
- Start scheduling no earlier than: ${startDate}
- Assignment title: ${title}
- Due date: ${dueDateOnly} at ${dueTime}
- Effective deadline: ${effectiveDeadline}
${userEstimatedHours ? `- Student estimated total time: ${userEstimatedHours} hours.` : ''}
${school ? `- School: ${school}` : ''}
${yearInSchool ? `- Year in school: ${yearInSchool}` : ''}
${courseName ? `- Course: ${courseName}` : ''}
${courseLevel ? `- Course level: ${courseLevel}` : ''}
${assignmentType ? `- Assignment type: ${assignmentType}` : ''}
${difficulty ? `- Student perceived difficulty: ${difficulty}` : ''}

${courseProfile ? `Course profile:
${courseProfile}

Use the course profile for course-specific workload and assignment style. Do not let it override the current assignment details, due date, user requirements, or scheduling rules.` : ''}

${documentContent ? `Assignment document:
${documentContent}` : ''}

${photoText ? `Assignment photo text:
${photoText}` : ''}

${requirements ? `Specific requirements:
${requirements}` : ''}

Existing workload:
${workloadSummary}

Personal planning history:
${personalPlanningSummary}

Roadmap rules:
1. Break the assignment into useful steps in logical order.
2. Step descriptions must be concrete and action-based, 1–2 short sentences. Do not restate the title.
3. Do not invent course-specific details unless they appear in the current task, course profile, document/photo text, requirements, or planning history.
4. Do not include generic setup steps unless this is the first assignment in a series or setup is clearly required.
5. Steps must stay in chronological order; a later step cannot be scheduled before an earlier step.

Date rules:
- All recommendedDate values must be between ${startDate} and ${effectiveDeadline}, inclusive. The effective deadline is the final allowed work day.
- Due-before-noon: the effective deadline is already set to the day before the actual due date when due time is before noon, so no work should land on the actual due date.

Scheduling rules:
First decide the number of work sessions. A work session is one sitting and can contain multiple steps. The workSession field is the sitting number, not the step number. Multiple steps can share the same workSession and recommendedDate when done in one sitting.

Hard required session counts:
- 0–1.5 hours: 1 session
- >1.5–3 hours: 1–2 sessions
- >3–6 hours: 2 sessions
- >6–10 hours: 3 sessions
- >10–15 hours: 4–5 sessions
- >15–25 hours: 5–7 sessions
- >25 hours: fewest practical sessions while avoiding overloaded days

Only exceed the default if the assignment explicitly requires waiting, intermediate deadlines, separate phases, or existing workload makes the default impossible.

Session placement:
You have, for every day between ${startDate} and ${effectiveDeadline}, how many hours are already scheduled. Use it.

The key is finding low workload days as close to the deadline as possible.

Pick the days that keep each day's total workload low. A day that is free or light is better than a day that is already busy. Do not add work to a day that is already heavy when a clear day is available in the window.

Spread sessions across different days rather than piling them onto one day.

Do not start unreasonably early. Only use the part of the window you actually need — a small assignment does not need to begin a week or more ahead. Stay near the deadline, but never at the cost of cramming a day that already has a lot on it. Avoiding an overloaded day always beats finishing later.

The session count from the table above is fixed; placement never changes it. A 1-session assignment is done on one day and is never split.

Keep sessions intact — all steps in a session stay on one day. Only break this pattern when the work genuinely requires waiting — feedback, data collection, an intermediate deadline.

Time estimate rules:
${userEstimatedHours
    ? `totalEstimatedHours and the sum of all step estimatedHours must each equal exactly ${userEstimatedHours}.`
    : 'Be realistic and conservative. Most ordinary assignments take 1–4 hours unless the task clearly requires more.'}

Return ONLY valid JSON in this exact format:
{
  "totalEstimatedHours": number,
  "steps": [
    {
      "title": "Step title",
      "description": "A concrete 1–2 sentence action description",
      "estimatedHours": number,
      "recommendedDate": "YYYY-MM-DD",
      "schedulingReason": "Brief explanation of why this specific date was chosen given the workload data",
      "priority": "High" | "Medium" | "Low",
      "workSession": number
    }
  ]
}
`

    const response = await fetch('https://api.openai.com/v1/chat/completions', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${Deno.env.get('OPENAI_API_KEY')}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: 'gpt-4o',
        messages: [{ role: 'user', content: prompt }],
        response_format: { type: 'json_object' },
      }),
    })

    const data = await response.json()

    console.log('OpenAI usage:', JSON.stringify(data.usage))
    console.log('OpenAI response:', JSON.stringify(data))

    if (!response.ok) {
      const errorMessage = data?.error?.message || 'OpenAI request failed'

      await updateAiUsageEvent({
        usageEventId,
        success: false,
        errorMessage,
      })

      return jsonResponse(
        {
          error: 'ROADMAP_GENERATION_FAILED',
          message: 'Couldn’t generate roadmap. Please try again.',
        },
        500,
      )
    }

    const result = JSON.parse(data.choices[0].message.content)

    console.log('Parsed result:', JSON.stringify(result))

    const finalResult = clampRoadmapDatesToAllowedRange(
      result,
      startDate,
      effectiveDeadline,
    )

    console.log('Final result after date bounds check:', JSON.stringify(finalResult))

    await updateAiUsageEvent({
      usageEventId,
      success: true,
    })

    return jsonResponse(finalResult)
  } catch (error) {
    console.error('Roadmap generation error:', error)

    await updateAiUsageEvent({
      usageEventId,
      success: false,
      errorMessage: error instanceof Error ? error.message : String(error),
    })

    return jsonResponse(
      {
        error: 'ROADMAP_GENERATION_FAILED',
        message: 'Couldn’t generate roadmap. Please try again.',
      },
      500,
    )
  }
})
