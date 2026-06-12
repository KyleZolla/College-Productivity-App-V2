const AI_EVENT_TYPE_COURSE_SYLLABUS_PROFILE = 'course_syllabus_profile'
const COURSE_SYLLABUS_PROFILE_LIMIT = 10
const COURSE_SYLLABUS_PROFILE_WINDOW_DAYS = 90

// --- Input size limits ---------------------------------------------------
// The Android client extracts at most 120k chars from a document and caps
// typed fields well below these values, so legitimate users never hit them.
// They exist to stop direct API callers from sending maximally expensive
// payloads to OpenAI.
const MAX_BODY_CHARS = 500_000
const STRING_LIMITS: Record<string, number> = {
  courseName: 200,
  courseLevel: 50,
  courseSyllabus: 150_000,
}

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
// -------------------------------------------------------------------------

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
          message: 'You must be signed in to generate a course profile.',
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
          message: 'You must be signed in to generate a course profile.',
        },
        401,
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

    const { courseName, courseLevel, courseSyllabus, courseId } = body as {
      courseName?: string
      courseLevel?: string
      courseSyllabus?: string
      courseId?: unknown
    }

    if (!courseSyllabus || typeof courseSyllabus !== 'string' || courseSyllabus.trim().length === 0) {
      return jsonResponse({ error: 'courseSyllabus is required' }, 400)
    }

    const usageCount = await countAiUsageEvents(
      userId,
      AI_EVENT_TYPE_COURSE_SYLLABUS_PROFILE,
      COURSE_SYLLABUS_PROFILE_WINDOW_DAYS,
    )

    console.log('Course syllabus profile AI usage count:', usageCount)

    if (usageCount >= COURSE_SYLLABUS_PROFILE_LIMIT) {
      return jsonResponse(
        {
          error: 'AI_LIMIT_REACHED',
          message: 'You’ve used your 10 free syllabus profiles in the last 90 days. You can still add the course without AI syllabus analysis.',
        },
        429,
      )
    }

    usageEventId = await createAiUsageEvent({
      userId,
      eventType: AI_EVENT_TYPE_COURSE_SYLLABUS_PROFILE,
      courseId: asNullableNumber(courseId),
    })

    const prompt = `
You are helping create a short planning profile for a college course.

The profile will be saved and reused later when generating AI roadmaps for assignments in this course.

Your job is NOT to summarize the syllabus for a student.
Your job is to extract only the information that would help an AI planner create better assignment roadmaps.

Course information:
${courseName ? `Course Name: ${courseName}` : ''}
${courseLevel ? `Course Level: ${courseLevel}` : ''}

Full Course Syllabus:
${courseSyllabus}

Create a concise course planning profile.

Focus on:
- what kind of course this is
- typical assignments or deliverables
- how much work assignments may require
- grading/rubric expectations
- submission expectations
- late policy or deadline rules
- recurring assignment patterns
- exam/project/paper/lab expectations
- professor-specific instructions that affect planning
- anything that should make future roadmap steps more accurate

Do not include:
- generic university policy language
- long lists of dates
- long lists of readings
- irrelevant boilerplate
- accessibility/accommodation/legal text unless it affects assignment planning
- full syllabus summary

Keep the profile compact but useful.
Keep the courseProfile under 600 words.
Format the courseProfile as a clear structured text block with short sections.

Respond ONLY with a JSON object in this exact format:
{
  "courseProfile": "Course type: ...\\nTypical assignments: ...\\nPlanning implications: ...\\nSubmission/deadline rules: ...\\nWorkload expectations: ...\\nImportant notes for roadmap generation: ..."
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
        max_tokens: 900,
      }),
    })

    const data = await response.json()

    console.log('Course profile OpenAI usage:', JSON.stringify(data.usage))

    if (!response.ok) {
      console.log('OpenAI error:', JSON.stringify(data))

      const errorMessage = data?.error?.message || 'OpenAI request failed'

      await updateAiUsageEvent({
        usageEventId,
        success: false,
        errorMessage,
      })

      return jsonResponse(
        {
          error: 'COURSE_PROFILE_GENERATION_FAILED',
          message: 'Couldn’t generate course profile. Please try again.',
        },
        500,
      )
    }

    const content = data.choices?.[0]?.message?.content

    if (!content) {
      await updateAiUsageEvent({
        usageEventId,
        success: false,
        errorMessage: 'No content returned from OpenAI',
      })

      return jsonResponse(
        {
          error: 'COURSE_PROFILE_GENERATION_FAILED',
          message: 'Couldn’t generate course profile. Please try again.',
        },
        500,
      )
    }

    let result: any

    try {
      result = JSON.parse(content)
    } catch (error) {
      await updateAiUsageEvent({
        usageEventId,
        success: false,
        errorMessage: error instanceof Error ? error.message : String(error),
      })

      return jsonResponse(
        {
          error: 'COURSE_PROFILE_GENERATION_FAILED',
          message: 'Couldn’t generate course profile. Please try again.',
        },
        500,
      )
    }

    if (!result.courseProfile || typeof result.courseProfile !== 'string') {
      await updateAiUsageEvent({
        usageEventId,
        success: false,
        errorMessage: 'Invalid course profile response',
      })

      return jsonResponse(
        {
          error: 'COURSE_PROFILE_GENERATION_FAILED',
          message: 'Couldn’t generate course profile. Please try again.',
        },
        500,
      )
    }

    await updateAiUsageEvent({
      usageEventId,
      success: true,
    })

    return jsonResponse({
      courseProfile: result.courseProfile,
    })
  } catch (error) {
    console.log('generate-course-profile error:', error)

    await updateAiUsageEvent({
      usageEventId,
      success: false,
      errorMessage: error instanceof Error ? error.message : String(error),
    })

    return jsonResponse(
      {
        error: 'COURSE_PROFILE_GENERATION_FAILED',
        message: 'Couldn’t generate course profile. Please try again.',
      },
      500,
    )
  }
})
