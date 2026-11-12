<script setup lang="ts">
/**
 * Task transcript and controls (spec §10.4 steps 3–4).
 *
 * Review actions are sent as the user by default — the backend lets the user review
 * anything — while the manager who *would* own the review is named so the hierarchy stays
 * visible. A human assignee's own moves (claim, complete) are sent as that member, which is
 * what puts their name rather than "You" on the transcript entry.
 *
 * The transcript renders an agent's prose as the Markdown it is written in (`MarkdownText`),
 * and raw payloads — tool results, tracebacks — as the text they are.
 *
 * Two things share this panel with the transcript. The **status picker** in the header is
 * the manual move — the lifecycle buttons in the footer stay the ones that carry meaning,
 * and this covers what they do not, which for a person's task is most of it. The **edit
 * form** patches only the fields that actually changed, like `MemberEditor`, so two people
 * touching different fields of one card do not overwrite each other.
 *
 * The transcript is also the task's history: status changes and edits are entries in it,
 * always visible, rather than a separate log nobody would open — which is exactly what
 * **Reset** throws away, along with the session id behind it, when a run has gone somewhere
 * the user does not want resumed from.
 */
import {
  Archive,
  ArrowRight,
  Ban,
  Check,
  Copy,
  Minimize2,
  LoaderCircle,
  ExternalLink,
  FileText,
  GitBranch,
  Hand,
  Hourglass,
  ListRestart,
  Paperclip,
  Pencil,
  Play,
  RotateCcw,
  Send,
  Smartphone,
  Trash2,
  Unplug,
  X,
} from 'lucide-vue-next'
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { taskAttachmentUrl, uploadTaskAttachment, api } from '@/api/client'
import type {
  TaskAttachment,
  TaskDetail,
  TaskResultType,
  TaskStatus,
  TaskUpdate,
} from '@/api/types'
import DependencyPicker from '@/components/teams/DependencyPicker.vue'
import MemberMark from '@/components/teams/MemberMark.vue'
import MemberSelect from '@/components/teams/MemberSelect.vue'
import RunOverrides from '@/components/teams/RunOverrides.vue'
import SessionStatus from '@/components/teams/SessionStatus.vue'
import SubagentSummary from '@/components/teams/SubagentSummary.vue'
import TaskChanges from '@/components/teams/TaskChanges.vue'
import TaskDiff from '@/components/teams/TaskDiff.vue'
import TaskWritePaths from '@/components/teams/TaskWritePaths.vue'
import AttachmentPicker from '@/components/ui/AttachmentPicker.vue'
import ConversationPanel from '@/components/teams/ConversationPanel.vue'
import ModelEffortSlider from '@/components/ui/ModelEffortSlider.vue'
import SegmentedControl from '@/components/ui/SegmentedControl.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import TokenUsageChip from '@/components/ui/TokenUsageChip.vue'
import AgentBackendIcon from '@/components/ui/AgentBackendIcon.vue'
import ContextUsageBar from '@/components/ui/ContextUsageBar.vue'
import { t } from '@/i18n'
import { dialogEscape } from '@/lib/dialog'
import {
  formatBytes,
  formatCountdown,
  formatDuration,
  formatTime,
  parseUtc,
  statusLabel,
} from '@/lib/status'
import { subagents } from '@/lib/subagents'
import { useTaskClock } from '@/lib/task-clock'
import {
  RESULT_TYPES,
  resultTypeColor,
  resultTypeHint,
  resultTypeLabel,
} from '@/lib/task-result'
import { taskUsage } from '@/lib/task-usage'
import { writePathsFromText } from '@/lib/task-write-paths'
import type { TranscriptMessage } from '@/lib/transcript'
import { useTeamsStore } from '@/stores/teams'

const props = defineProps<{ task: TaskDetail; embedded?: boolean }>()
const emit = defineEmits<{ close: [] }>()

const teams = useTeamsStore()
// §10.3: read-only effort uses the same installed catalogue as its editor.
onMounted(() => void teams.loadOptions())
const comment = ref('')

/**
 * The review box keeps what was typed into it and never sent.
 *
 * A comment is written while reading the transcript beside it, which is exactly where
 * closing the dialog, opening another task or reloading the page used to throw it away —
 * and on Request changes the box is *required*, so what it loses is the whole action. Kept
 * per task in `localStorage` beside the verbosity dial rather than on the server: it is a
 * half-finished sentence of the reader's, worth nothing to the project and not worth a
 * round trip. Cleared when it is sent and when it is emptied by hand, so the store does not
 * fill with keys for tasks nobody is reviewing any more.
 */
const DRAFT_PREFIX = 'voxarium_studio.task.comment.'
const draftKey = computed(() => `${DRAFT_PREFIX}${props.task.id}`)

watch(
  draftKey,
  (key) => {
    try {
      comment.value = window.localStorage.getItem(key) ?? ''
    } catch {
      comment.value = ''
    }
  },
  { immediate: true },
)

watch(comment, (text) => {
  try {
    if (text) window.localStorage.setItem(draftKey.value, text)
    else window.localStorage.removeItem(draftKey.value)
  } catch {
    // A disabled or full store costs the draft, never the review itself.
  }
})

// ---------------------------------------------------------------- review attachments

/**
 * Files attached to the review comment (§10.4): uploaded as they arrive — a drop onto the
 * box, a paste, the picker — and named by the review when it is sent, so a failed send
 * leaves spare files rather than a half-sent review. Kept beside the draft in
 * `localStorage` for the draft's reason: a screenshot dropped in and lost to a closed
 * dialog would have to be found and dropped again.
 */
const ATTACHMENTS_PREFIX = 'voxarium_studio.task.attachments.'
const attachmentsKey = computed(() => `${ATTACHMENTS_PREFIX}${props.task.id}`)
const attachments = ref<TaskAttachment[]>([])
const uploading = ref(0)
const attachmentError = ref<string | null>(null)
const filePicker = ref<HTMLInputElement | null>(null)
const dragging = ref(false)

watch(
  attachmentsKey,
  (key) => {
    attachmentError.value = null
    try {
      const raw = window.localStorage.getItem(key)
      const parsed: unknown = raw ? JSON.parse(raw) : []
      attachments.value = Array.isArray(parsed) ? parsed.filter(isAttachment) : []
    } catch {
      attachments.value = []
    }
  },
  { immediate: true },
)

watch(
  attachments,
  (list) => {
    try {
      if (list.length) window.localStorage.setItem(attachmentsKey.value, JSON.stringify(list))
      else window.localStorage.removeItem(attachmentsKey.value)
    } catch {
      // A disabled or full store costs the memory, never the attachment itself.
    }
  },
  { deep: true },
)

function isAttachment(value: unknown): value is TaskAttachment {
  if (!value || typeof value !== 'object') return false
  const entry = value as Record<string, unknown>
  return typeof entry.name === 'string' && typeof entry.path === 'string'
}

/** The attachments a review entry carries, for the transcript. */
function attachmentsOf(message: TranscriptMessage): TaskAttachment[] {
  const raw = message.data?.attachments
  return Array.isArray(raw) ? raw.filter(isAttachment) : []
}

function attachmentUrl(name: string): string {
  return teams.team ? taskAttachmentUrl(teams.team.id, props.task.id, name) : ''
}

async function attachFiles(files: Iterable<File>): Promise<void> {
  const teamId = teams.team?.id
  if (!teamId) return
  attachmentError.value = null
  for (const file of files) {
    uploading.value += 1
    try {
      const stored = await uploadTaskAttachment(teamId, props.task.id, file)
      attachments.value = [...attachments.value, stored]
    } catch (cause) {
      attachmentError.value = cause instanceof Error ? cause.message : String(cause)
    } finally {
      uploading.value -= 1
    }
  }
}

function pickFiles(event: Event): void {
  const input = event.target as HTMLInputElement
  if (input.files?.length) void attachFiles(Array.from(input.files))
  // Cleared so the same file can be picked again after it was removed.
  input.value = ''
}

/** `Ctrl+V` with a picture or a file on the clipboard; text pastes as text. */
function onPaste(event: ClipboardEvent): void {
  const files = Array.from(event.clipboardData?.files ?? [])
  if (!files.length) return
  event.preventDefault()
  void attachFiles(files)
}

function hasFiles(event: DragEvent): boolean {
  return Boolean(event.dataTransfer?.types.includes('Files'))
}

function onDragOver(event: DragEvent): void {
  if (!hasFiles(event)) return
  event.preventDefault()
  dragging.value = true
}

function onDrop(event: DragEvent): void {
  dragging.value = false
  if (!hasFiles(event)) return
  event.preventDefault()
  void attachFiles(Array.from(event.dataTransfer?.files ?? []))
}

async function removeAttachment(entry: TaskAttachment): Promise<void> {
  attachments.value = attachments.value.filter((item) => item.name !== entry.name)
  const teamId = teams.team?.id
  if (!teamId) return
  try {
    await api.removeTaskAttachment(teamId, props.task.id, entry.name)
  } catch {
    // The review no longer names it; a file left on disk is the lesser cost.
  }
}

// §10.4: the socket updates the counters while the backend is still running.
const usage = computed(() => taskUsage(props.task))

const STATUSES: TaskStatus[] = [
  'todo',
  'pending',
  'in_progress',
  'needs_review',
  'done',
  'blocked',
  'cancelled',
]

const manager = computed(() => teams.managerOf(props.task.assignee_id))
const delegatedBy = computed(() =>
  props.task.created_by ? teams.memberById.get(props.task.created_by) : null,
)
const isHuman = computed(() => props.task.assignee?.kind === 'human')
const isCompressing = computed(() =>
  props.task.is_compacting || teams.compressingTaskId === props.task.id,
)
const compressionHint = computed(() => isCompressing.value
  ? t('taskDetail.compression.inProgress')
  : props.task.is_running
    ? t('taskDetail.compression.wait')
    : t('taskDetail.compression.hint'),
)
const hasCompressibleDialogue = computed(() => props.task.messages.some(
  message => ['text', 'note', 'summary'].includes(message.kind) && message.text.trim(),
))
const actorId = computed(() => (isHuman.value ? props.task.assignee_id : null))

const canReview = computed(() => props.task.status === 'needs_review')

/**
 * What the task runs on, resolved the way the orchestrator resolves it (§10.3): the task's
 * own value where it has one, the assignee's otherwise. Always shown for an agent's task —
 * which model did the work is half of reading the cost beside it — and the tooltip says
 * whether the pair was chosen on this card or inherited from the role. Absent for a person,
 * who has no session and so neither setting.
 */
const runSettings = computed(() => {
  if (isHuman.value) return null
  const member = props.task.assignee ?? teams.memberById.get(props.task.assignee_id) ?? null
  const backend = props.task.agent_backend ?? member?.agent_backend ?? 'claude_code'
  const model = props.task.model ?? (backend === member?.agent_backend ? member?.model : t('chat.defaultModel')) ?? null
  const effort = props.task.effort ?? member?.effort ?? null
  if (!model && !effort && !props.task.agent_backend) return null
  const describe = (m: string | null, e: string | null) =>
    [m, e ? t('taskDetail.runSettings.effort', { effort: e }) : null].filter(Boolean).join(' · ')
  const overridden = Boolean(props.task.agent_backend || props.task.model || props.task.effort)
  // A member the board cannot name is "the member" — a sentence of its own in each language,
  // since a word dropped into the named one would need a case Russian cannot give it.
  const name = member?.name ?? null
  const usual = describe(member?.model ?? null, member?.effort ?? null)
  let title: string
  if (!overridden) {
    title =
      name === null
        ? t('taskDetail.runSettings.inheritedMember')
        : t('taskDetail.runSettings.inherited', { name })
  } else if (!usual) {
    title = t('taskDetail.runSettings.overridden')
  } else {
    title =
      name === null
        ? t('taskDetail.runSettings.overriddenByMember', { usual })
        : t('taskDetail.runSettings.overriddenBy', { name, usual })
  }
  return {
    model,
    effort,
    backend,
    overridden,
    title,
  }
})

/** A session runs from Pending and nowhere else (§10.4), so that is where Start lives. */
const canStart = computed(
  () => !isHuman.value && !props.task.is_running && !isCompressing.value && props.task.status === 'pending',
)

/** A person may pick their own work up straight out of Todo — no session is involved. */
const canClaim = computed(
  () => isHuman.value && ['todo', 'pending', 'blocked'].includes(props.task.status),
)
const canComplete = computed(() => isHuman.value && props.task.status === 'in_progress')

/** Dependencies still holding this task back, resolved against the board. */
const dependencies = computed(() =>
  props.task.depends_on.map((id) => {
    const found = teams.tasks.find((candidate) => candidate.id === id)
    return {
      id,
      displayId: found?.display_id ?? '',
      title: found?.title ?? id,
      status: found?.status ?? 'pending',
      met: found?.status === 'done',
    }
  }),
)
const blockedBy = computed(() => dependencies.value.filter((entry) => !entry.met))

/**
 * Resume and Start are the same button and not the same act, which is worth saying in the
 * tooltip: a resume reopens the session the last run left, so the brief is already in its
 * context and only notes written since then are sent with it.
 */
const startHint = computed(() => {
  if (blockedBy.value.length)
    return t('taskDetail.actions.waitingFor', {
      tasks: blockedBy.value.map((entry) => entry.title).join(', '),
    })
  return props.task.agent_session_id
    ? t('taskDetail.actions.resumeHint')
    : t('taskDetail.actions.startHint')
})

/**
 * Moving an agent task into Pending — the gesture the Todo column exists to require (§10.4).
 * It takes the place of Start rather than sitting beside it, since Start is refused anywhere
 * else; the status control in the header can do the same move, but it is an invisible
 * `<select>` over the badge, and the one deliberate act in this flow should not be hidden
 * behind that. `blocked` gets the same button because a retry is the same move.
 *
 * Never on a person's task: Claim already takes it straight out of Todo, and there is no
 * session for the Pending column to gate.
 */
const canRelease = computed(
  () => !isHuman.value && ['todo', 'blocked'].includes(props.task.status),
)
const releaseLabel = computed(() =>
  props.task.status === 'blocked'
    ? t('taskDetail.actions.releaseBack')
    : t('taskDetail.actions.release'),
)
const releaseHint = computed(() => {
  if (!props.task.auto_start) return t('taskDetail.actions.releaseHint')
  return blockedBy.value.length
    ? t('taskDetail.actions.releaseHintWaits')
    : t('taskDetail.actions.releaseHintStarts')
})

// ---------------------------------------------------------------- remote control

/**
 * Remote Control (§10.8): hand the running session to claude.ai/code and the phone.
 *
 * The button is live only while the session is — the bridge belongs to the CLI subprocess
 * the backend is driving, so on a task that is waiting or finished there is nothing to hand
 * over. Saying that in the tooltip is the point; a button that fails on click would leave
 * the user guessing whether the feature or their account is the problem.
 */
const remoteLink = computed(() => props.task.remote_control_url)
const canRemoteControl = computed(() => !isHuman.value && !isCompressing.value && props.task.is_running === true)
const remoteHint = computed(() => {
  if (isHuman.value) return t('taskDetail.remote.person')
  if (isCompressing.value) return t('taskDetail.compression.inProgress')
  if (remoteLink.value) return t('taskDetail.remote.linked')
  if (!props.task.is_running) return t('taskDetail.remote.notRunning')
  return t('taskDetail.remote.ready')
})

const copied = ref(false)
const linkField = ref<HTMLInputElement | null>(null)
let copyReset: ReturnType<typeof setTimeout> | undefined

async function copyLink(): Promise<void> {
  const url = remoteLink.value
  if (!url) return
  try {
    await navigator.clipboard.writeText(url)
    copied.value = true
    clearTimeout(copyReset)
    copyReset = setTimeout(() => (copied.value = false), 1500)
  } catch {
    // No clipboard permission (or an insecure context): select it so the user still can.
    linkField.value?.select()
  }
}

// ---------------------------------------------------------------- waiting out a usage limit

/**
 * Wait for the account's usage window to refill rather than leaving the task Blocked (§10.3).
 *
 * The switch is beside Follow rather than in the edit form because it is a decision about a
 * *run*, and the one moment anybody makes it is while reading a transcript that has just
 * stopped mid-work. Never offered on a person's task: there is no session to be refused.
 */
const canWaitOutLimit = computed(() => !isHuman.value)

async function setResumeOnLimit(enabled: boolean): Promise<void> {
  await teams.updateTask(props.task.id, { resume_on_limit: enabled })
}

/**
 * Tick while a task is running or waiting for its usage window. A quiet Codex run may
 * emit no events for minutes; its elapsed time still has to advance on screen (§10.6).
 */
const now = useTaskClock(() => props.task)

/**
 * The countdown, and what its number means.
 *
 * It follows the **reset** while the reset is still ahead — that is what the task is waiting
 * for — and switches to the restart once it has passed, since "resets in now" would say
 * nothing for the couple of minutes of grace after it. A limit the runtime never dated has no
 * countdown at all, only the reason, which the tooltip carries either way.
 */
const limitWait = computed(() => {
  const reset = props.task.limit_reset_at
  const resume = props.task.limit_resume_at
  if (!reset && !resume) return null
  const ahead = reset !== null && parseUtc(reset) > now.value
  return {
    armed: Boolean(resume),
    text: ahead
      ? t('taskDetail.limit.resetsIn', { time: formatCountdown(reset, now.value) })
      : resume
        ? t('taskDetail.limit.resumingIn', { time: formatCountdown(resume, now.value) })
        : t('taskDetail.limit.resetPassed'),
    title: [
      reset
        ? t('taskDetail.limit.resetsAt', { time: formatTime(reset) })
        : t('taskDetail.limit.resetUnknown'),
      resume
        ? t('taskDetail.limit.resumesAt', { time: formatTime(resume) })
        : t('taskDetail.limit.tickToResume'),
    ].join(' '),
  }
})

/**
 * The sub-agents and workflows this session started (§10.3) — folded and drawn by
 * `SubagentSummary`, in the Status column beside the transcript (§10.4), or in the header
 * of a Producer page card, which has no column to hold it.
 */
const delegated = computed(() => subagents(props.task.messages, props.task.subagents))

/** Documents the agent wrote while working — the durable half of what it produced. */
const writtenDocs = computed(() => {
  const seen = new Map<string, string>()
  for (const message of props.task.messages) {
    if (message.kind !== 'doc') continue
    const path = String(message.data?.path ?? '')
    if (path) seen.set(path, String(message.data?.title ?? path))
  }
  return [...seen].map(([path, title]) => ({ path, title }))
})

// ---------------------------------------------------------------- what the task changed

/**
 * The file whose diff is showing instead of the transcript, or `null` for the transcript.
 *
 * Owned here rather than in the column because it decides what the *main pane* renders —
 * the column only reports the click. Cleared when the task changes, since a path belongs
 * to one task's checkout and would otherwise be requested against the next one's.
 */
const selectedFile = ref<string | null>(null)

watch(() => props.task.id, () => (selectedFile.value = null))

/**
 * Bumped when the list is worth reading again — and deliberately not on every message.
 *
 * Re-reading costs a `git diff` over the whole workspace, and a live session appends every
 * few seconds; tying it to the transcript would run one subprocess per line an agent
 * writes. A status change is the coarse event that actually matters (a run ending is the
 * one that changes the answer), and the column has a refresh button for the rest.
 */
const changesRevision = ref(0)
watch(
  () => [props.task.status, props.task.is_running],
  () => (changesRevision.value += 1),
)

// ---------------------------------------------------------------- editing

interface Draft {
  title: string
  brief: string
  write_paths: string
  assignee_id: string
  depends_on: string[]
  auto_start: boolean
  /** Null inherits the assignee's runtime choice (§10.3). */
  model: string | null
  backend: string | null
  effort: string | null
}

const editing = ref(false)
const confirmingRemoval = ref(false)
const confirmingReset = ref(false)

/**
 * `Escape` unwinds one layer at a time.
 *
 * An open form here is a layer of its own — the editor, and either confirmation — and the
 * dialog closes only once none of them is up. Otherwise one key does two things: dismissing
 * a confirmation nobody meant to raise *and* throwing away a half-typed brief behind it.
 */
inject(dialogEscape, null)?.(() => {
  if (editing.value) closeEditor()
  else if (confirmingReset.value) confirmingReset.value = false
  else if (confirmingRemoval.value) confirmingRemoval.value = false
  else return false
  return true
})
const draft = ref<Draft>(draftOf())

function draftOf(): Draft {
  return {
    title: props.task.title,
    brief: props.task.brief,
    write_paths: (props.task.write_paths ?? []).join('\n'),
    assignee_id: props.task.assignee_id,
    depends_on: [...props.task.depends_on],
    auto_start: props.task.auto_start,
    model: props.task.model,
    backend: props.task.agent_backend ?? null,
    effort: props.task.effort,
  }
}

// Re-seed when the rail is pointed at another task, never mid-edit on the same one.
watch(
  () => props.task.id,
  () => {
    // Start the outgoing task's brief on its way before anything is re-seeded, and do not
    // await it: `flushBrief` snapshots the id synchronously, so the patch still lands on the
    // task it was typed into, while awaiting here would hold the switch on a round-trip.
    void flushBrief()
    editing.value = false
    confirmingRemoval.value = false
    confirmingReset.value = false
    copied.value = false
    draft.value = draftOf()
  },
)

const draftAssigneeIsHuman = computed(() => teams.isHuman(draft.value.assignee_id))

/** Only what changed — an untouched field is left to whatever the board says it is now. */
const patch = computed<TaskUpdate>(() => {
  const current = draft.value
  const original = props.task
  const result: TaskUpdate = {}
  if (current.title.trim() !== original.title) result.title = current.title.trim()
  if (current.brief !== original.brief) result.brief = current.brief
  const writePaths = writePathsFromText(current.write_paths)
  if (JSON.stringify(writePaths) !== JSON.stringify(original.write_paths ?? []))
    result.write_paths = writePaths
  if (current.assignee_id !== original.assignee_id) result.assignee_id = current.assignee_id
  if (current.depends_on.join(' ') !== original.depends_on.join(' '))
    result.depends_on = current.depends_on
  if (current.auto_start !== original.auto_start) result.auto_start = current.auto_start
  // Null is sent, not omitted: it drops an override and restores the assignee's setting.
  if (current.model !== original.model) result.model = current.model
  if (current.backend !== (original.agent_backend ?? null)) result.agent_backend = current.backend
  if (current.effort !== original.effort) result.effort = current.effort
  return result
})

const dirty = computed(() => Object.keys(patch.value).length > 0)

function openEditor() {
  draft.value = draftOf()
  briefFailed.value = false
  editing.value = true
}

async function saveEdit() {
  // The patch about to go carries the brief as well, so whatever the debounce is holding is
  // superseded rather than sent a second time.
  cancelBrief()
  if (!dirty.value || !draft.value.title.trim()) return
  if (await teams.updateTask(props.task.id, patch.value)) editing.value = false
}

/**
 * The brief writes itself back; every other field waits for **Save**.
 *
 * The brief is the field the form is opened for and the one that takes minutes to write —
 * it is what the agent is handed — so losing it to a stray `Escape` or a click outside is
 * the expensive mistake this form can make. The rest of the fields are one gesture each
 * (a picker, a checkbox) and are cheap to redo, so they keep the explicit Save, which is
 * also what keeps a half-chosen assignee from being written the moment it is touched.
 *
 * Each save is an entry in the task's history (`_record_edit` on the backend), which is why
 * this delay is longer than a document editor's: consecutive edits by the same person are
 * folded into one entry there, and the debounce is what keeps the whole session inside that
 * window rather than trickling into it.
 */
const BRIEF_SAVE_DELAY = 1200

let briefTimer: ReturnType<typeof setTimeout> | null = null
/** Snapshotted with the id it was typed into — the dialog can be pointed at another task
 *  before the timer fires, and a brief must never land on whoever came next. */
let briefPending: { taskId: string; brief: string } | null = null
/** Saves run one at a time: each ends in a refresh, so two in flight can land out of order
 *  and leave the store on the older payload — the same rule `MemberEditor` follows. */
let briefQueue: Promise<unknown> = Promise.resolve()
let briefRunning = 0

const briefSaving = ref(false)
const briefFailed = ref(false)

/** Typed and not yet written. `props.task` is what the backend last confirmed. */
const briefDirty = computed(() => editing.value && draft.value.brief !== props.task.brief)

function cancelBrief(): void {
  if (briefTimer) clearTimeout(briefTimer)
  briefTimer = null
  briefPending = null
}

function scheduleBrief(): void {
  if (!briefDirty.value) {
    // Includes the re-seed after a save landed: the draft matches the task again, and a
    // timer left running would send a patch with nothing in it.
    cancelBrief()
    return
  }
  if (briefTimer) clearTimeout(briefTimer)
  briefFailed.value = false
  briefPending = { taskId: props.task.id, brief: draft.value.brief }
  briefTimer = setTimeout(() => void flushBrief(), BRIEF_SAVE_DELAY)
}

/** Write what is pending, if anything is. The snapshot is taken synchronously. */
async function flushBrief(): Promise<void> {
  if (briefTimer) clearTimeout(briefTimer)
  briefTimer = null
  const job = briefPending
  briefPending = null
  if (!job) return
  briefRunning += 1
  briefSaving.value = true
  const attempt = briefQueue.then(() => teams.updateTask(job.taskId, { brief: job.brief }))
  briefQueue = attempt
  try {
    briefFailed.value = (await attempt) === false
  } finally {
    briefRunning -= 1
    if (briefRunning === 0) briefSaving.value = false
  }
}

watch(() => draft.value.brief, scheduleBrief)

/**
 * Closing the form is not "discard".
 *
 * Cancel, `Escape` and the header's toggle all land here, and by the time any of them is
 * pressed most of the brief is already saved — so dropping the last second of typing would
 * leave half a sentence written and the rest gone, which is worse than either. The other
 * fields still revert, because they were never sent.
 */
function closeEditor(): void {
  void flushBrief()
  editing.value = false
}

// A dialog that is going away still owes whatever the debounce is holding.
onBeforeUnmount(() => {
  void flushBrief()
})

async function setStatus(status: TaskStatus) {
  if (status === props.task.status) return
  await teams.updateTask(props.task.id, { status })
}

async function remove() {
  if (await teams.deleteTask(props.task.id)) emit('close')
  else confirmingRemoval.value = false
}

/** One confirmation open at a time — they sit in the same row and mean opposite things. */
function ask(what: 'reset' | 'removal') {
  confirmingReset.value = what === 'reset'
  confirmingRemoval.value = what === 'removal'
}

/**
 * Throw away everything the runs left and keep the task (§10.4). The brief, the assignee
 * and the dependencies survive; the session does not, which is what makes the next Start a
 * genuine start rather than a resume into context the user has just decided against.
 */
async function reset() {
  await teams.act('reset', props.task.id)
  confirmingReset.value = false
}

// ---------------------------------------------------------------- lifecycle moves

async function review(action: 'approve' | 'request-changes') {
  if (action === 'request-changes' && !comment.value.trim()) return
  if (uploading.value) return
  const names = action === 'request-changes' ? attachments.value.map((entry) => entry.name) : []
  const sent = await teams.act(action, props.task.id, comment.value.trim(), null, names)
  if (!sent) return
  comment.value = ''
  if (action === 'request-changes') {
    attachments.value = []
  } else {
    // Approved with files still in the box: they were never sent, so take them back.
    for (const entry of [...attachments.value]) await removeAttachment(entry)
  }
}

/**
 * How a person's work ended (§10.4) — the same three answers an agent gives `finish_task`,
 * required on the backend and chosen here before Submit. Green by default: the common
 * case is finished work, and the other two are the ones a person reaches for on purpose.
 */
const resultType = ref<TaskResultType>('done')
const resultChoices = computed(() =>
  RESULT_TYPES.map((value) => ({ value, label: resultTypeLabel(value) })),
)

/**
 * A message to the session while it works (§10.8): it is not stopped for it — the agent has
 * the words at its next step. The box is the review comment's, draft and all, because the
 * two never show together and a thought half-typed when the run ends is usually the review.
 */
const canMessage = computed(
  () => !isHuman.value && props.task.is_running === true && !isCompressing.value,
)
const messaging = ref(false)

async function sendMessage() {
  const text = comment.value.trim()
  if (!text || messaging.value) return
  messaging.value = true
  try {
    if (await teams.messageTask(props.task.id, text) && comment.value.trim() === text) comment.value = ''
  } finally {
    messaging.value = false
  }
}

function onMessageKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  void sendMessage()
}

async function complete() {
  await teams.act(
    'complete',
    props.task.id,
    comment.value.trim(),
    actorId.value,
    [],
    resultType.value,
  )
  comment.value = ''
}
</script>

<template>
  <!-- No border, no rounding and no background of its own: the dialog panel draws all
       three — two hairlines side by side is a double border, and a surface painted over
       the panel's own would hide the card fill it is drawn on. -->
  <div class="flex min-h-0 flex-1">
    <div class="flex min-w-0 flex-1 flex-col" :class="{ 'overflow-y-auto': embedded }">
      <header class="border-b border-line px-pane py-2">
        <div class="flex items-start gap-2">
          <div class="min-w-0 flex-1">
            <!-- Read-only, and there is nothing behind it to edit: the number is assigned
                 once at creation and is in no patch the API accepts (§10.2). The edit form
                 below has fields for everything that *is* editable, and no field for this. -->
            <p class="font-mono text-small text-faint">{{ task.display_id }}</p>
            <p class="truncate font-medium">{{ task.title }}</p>
            <p class="flex items-center gap-1.5 truncate text-small text-faint">
              <MemberMark :member="teams.memberById.get(task.assignee_id) ?? null" />
              <span class="truncate">
                {{ teams.memberById.get(task.assignee_id)?.name ?? task.assignee_id }}
                <template v-if="isHuman">{{ t('taskDetail.header.person') }}</template>
                <template v-if="delegatedBy">
                  · {{ t('taskDetail.header.from', { name: delegatedBy.name }) }}
                </template>
                <template v-if="manager">
                  · {{ t('taskDetail.header.reviewedBy', { name: manager.name }) }}
                </template>
              </span>
            </p>
          </div>
          <button
            class="grid size-6 shrink-0 place-items-center rounded-sm text-faint hover:bg-hover hover:text-primary"
            :class="editing ? 'bg-hover text-primary' : ''"
            :title="t('taskDetail.header.edit')"
            @click="editing ? closeEditor() : openEditor()"
          >
            <Pencil class="size-3.5" />
          </button>
          <slot name="header-action">
            <button
              class="grid size-6 shrink-0 place-items-center rounded-sm text-faint hover:bg-hover hover:text-primary"
              :title="t('common.close')"
              @click="emit('close')"
            >
              <X class="size-3.5" />
            </button>
          </slot>
        </div>

        <div class="mt-2 flex flex-wrap items-center gap-2">
          <!-- The status is set here as well as reached through the lifecycle buttons: a
               person's task spends most of its life in states no button covers. -->
          <label class="relative inline-flex items-center" :title="t('taskDetail.header.move')">
            <StatusBadge :status="task.status" />
            <select
              :value="task.status"
              class="absolute inset-0 cursor-pointer opacity-0"
              :disabled="teams.busy || task.is_running"
              @change="setStatus(($event.target as HTMLSelectElement).value as TaskStatus)"
            >
              <option v-for="status in STATUSES" :key="status" :value="status">
                {{ statusLabel(status) }}
              </option>
            </select>
          </label>
          <!-- How the work ended, beside the status it ended in (§10.4): done green,
               question blue, error red — the reviewer reads the colour before the summary.
               A task in review with no type is one whose session ended without saying,
               and the chip says that rather than guessing. -->
          <span
            v-if="task.result_type"
            class="vxs-badge inline-flex items-center gap-1.5 rounded-pill px-2 py-0.5 text-small font-medium"
            :style="{ color: resultTypeColor(task.result_type) }"
            :title="resultTypeHint(task.result_type)"
          >
            <span
              class="size-1.5 rounded-full"
              :style="{ background: resultTypeColor(task.result_type) }"
            />
            {{ resultTypeLabel(task.result_type) }}
          </span>
          <span
            v-else-if="task.status === 'needs_review' && !isHuman"
            class="vxs-badge inline-flex items-center gap-1.5 rounded-pill px-2 py-0.5 text-small text-faint"
            :title="t('resultType.missingHint')"
          >
            {{ t('resultType.missing') }}
          </span>
          <!-- Off the board and still counted (§10.4). Said here because the dialog can be
               reached from the Archived column, and a status picker on an archived task is a
               way back onto the board: any move off Done brings it back. -->
          <span
            v-if="task.archived"
            class="inline-flex items-center gap-1 rounded-pill bg-inset px-2 py-0.5 text-small text-faint"
            :title="t('taskDetail.header.archivedHint')"
          >
            <Archive class="size-3" /> {{ t('taskDetail.header.archived') }}
          </span>
          <span class="text-small text-faint">{{ formatDuration(task.started_at, task.finished_at, now) }}</span>
          <span
            v-if="task.worktree_path"
            class="inline-flex items-center gap-1 text-small text-info"
            :title="task.worktree_path"
          >
            <GitBranch class="size-3" /> {{ t('taskDetail.header.isolatedWorktree') }}
          </span>
          <!-- Next to the cost on purpose: the model is why the number beside it is what it
               is. Inherited settings read quietly; an override is lit, because it is usually
               why this card cost something other than the member's others did (§10.3). -->
          <span
            v-if="runSettings"
            class="inline-flex items-center gap-2 text-small"
            :class="runSettings.overridden ? 'text-info' : 'text-faint'"
            :title="runSettings.title"
          >
            <span v-if="runSettings.model" class="inline-flex items-center gap-1">
              <AgentBackendIcon :backend="runSettings.backend" class="size-3" /> {{ runSettings.model }}
            </span>
            <ModelEffortSlider
              :model-value="runSettings.effort"
              :levels="teams.options?.effort_levels"
              :default-title="t('taskDetail.header.effortSolverAtStart')"
              readonly
            />
          </span>
          <!-- §10.4: context occupancy and lifetime spending share the model's status row. -->
          <ContextUsageBar v-if="!isHuman && usage.contextTokens !== null"
            :tokens="usage.contextTokens" :window="usage.contextWindow" :target="{ teamId: task.team_id, taskId: task.id }" />
          <TokenUsageChip
            v-if="!isHuman"
            :input-tokens="usage.inputTokens"
            :output-tokens="usage.outputTokens"
            :cached-tokens="usage.cacheReadTokens"
            :cost-usd="task.total_cost_usd"
            :cost-messages="task.messages"
          />
          <!-- Beside the telemetry rather than next to its own checkbox: how long this task
               has been stopped for is a property of the run, and it is the first thing
               somebody opening a Blocked card is asking. Lit only while something is
               actually waiting — otherwise it is a fact about why, not a promise (§10.3). -->
          <span
            v-if="limitWait"
            class="inline-flex items-center gap-1 text-small"
            :class="limitWait.armed ? 'text-warning' : 'text-faint'"
            :title="limitWait.title"
          >
            <Hourglass class="size-3" /> {{ limitWait.text }}
          </span>
        </div>

        <!-- A Producer page card has no Status column, so what the run has delegated stays
             under its telemetry (§10.3, §10.11); the task dialog shows it in the column. -->
        <SubagentSummary
          v-if="embedded && delegated.length"
          class="mt-2 border-t border-line-subtle pt-1.5"
          :entries="delegated"
        />
      </header>

      <form
        v-if="editing"
        class="shrink-0 border-b border-line bg-elevated p-pane"
        @submit.prevent="saveEdit"
      >
        <div class="space-y-2">
          <input
            v-model="draft.title"
            :placeholder="t('taskDetail.edit.titlePlaceholder')"
            class="w-full rounded-md border border-line bg-inset px-2 py-1 outline-none focus:border-accent"
          />
          <textarea
            v-model="draft.brief"
            rows="3"
            :placeholder="t('taskDetail.edit.briefPlaceholder')"
            class="w-full resize-y rounded-md border border-line bg-inset px-2 py-1 outline-none focus:border-accent"
          />

          <TaskWritePaths v-model="draft.write_paths" :disabled="task.is_running" />

          <div>
            <span class="mb-1 block text-small text-faint">
              {{ t('taskDetail.edit.assignedTo') }}
            </span>
            <!-- There is no "nobody" row, so the picker never answers null; the fallback
                 keeps the draft's own type honest rather than widening it for a case that
                 cannot arise. -->
            <MemberSelect
              :model-value="draft.assignee_id"
              :members="teams.members"
              :label="t('taskDetail.edit.assignedTo')"
              :disabled="task.is_running"
              :title="task.is_running ? t('taskDetail.edit.reassignWhileRunning') : ''"
              @update:model-value="draft.assignee_id = $event ?? draft.assignee_id"
            />
          </div>

          <DependencyPicker v-model="draft.depends_on" :exclude-id="task.id" />

          <!-- A person has no session, so neither setting would reach anything (§10.2). -->
          <RunOverrides
            v-model:backend="draft.backend"
            v-model:model="draft.model"
            v-model:effort="draft.effort"
            :member="teams.memberById.get(draft.assignee_id) ?? null"
            :disabled="draftAssigneeIsHuman"
          />

          <div class="flex flex-wrap items-center gap-3">
            <label
              class="flex items-center gap-2"
              :class="draftAssigneeIsHuman ? 'cursor-not-allowed text-faint' : 'cursor-pointer'"
              :title="
                draftAssigneeIsHuman
                  ? t('taskDetail.edit.autoStartPerson')
                  : t('taskDetail.edit.autoStartAgent')
              "
            >
              <input
                v-model="draft.auto_start"
                type="checkbox"
                :disabled="draftAssigneeIsHuman"
                class="accent-[var(--vxs-accent)]"
              />
              {{ t('taskDetail.edit.autoStart') }}
            </label>
            <!-- What the Save button does *not* cover: the brief writes itself back, so the
                 button beside this can read "Saved" while a sentence is still in flight.
                 Named rather than a bare "Saving…" for exactly that reason — the two
                 indicators are about different fields. -->
            <p
              class="ml-auto shrink-0 text-small"
              :class="briefFailed ? 'text-warning' : 'text-faint'"
              :title="
                briefFailed
                  ? (teams.error ?? t('taskDetail.edit.briefFailedHint'))
                  : t('taskDetail.edit.briefHint')
              "
            >
              <template v-if="briefFailed">{{ t('taskDetail.edit.briefFailed') }}</template>
              <template v-else-if="briefSaving || briefDirty">
                {{ t('taskDetail.edit.briefSaving') }}
              </template>
              <template v-else>{{ t('taskDetail.edit.briefSaved') }}</template>
            </p>
            <button
              type="submit"
              class="vxs-btn-primary px-3 py-1.5 font-medium text-on-accent disabled:opacity-50"
              :disabled="teams.busy || !dirty || !draft.title.trim()"
            >
              {{ dirty ? t('common.save') : t('common.saved') }}
            </button>
            <button
              type="button"
              class="rounded-md border border-line px-3 py-1.5 hover:bg-hover"
              @click="closeEditor()"
            >
              {{ t('common.cancel') }}
            </button>
          </div>
        </div>
      </form>

      <!-- Picking a file in the column beside this swaps the pane rather than opening a
           second one: a patch wants the width the transcript has, and the two are read one
           at a time. The switch back is the same control that got here. -->
      <ConversationPanel :conversation-id="task.id" :messages="task.messages" :task-status="task.status"
        :embedded="embedded" :empty-text="isHuman ? t('taskDetail.transcript.emptyPerson')
          : task.status === 'todo' ? t('taskDetail.transcript.emptyTodo') : t('taskDetail.transcript.emptyAgent')">
        <template v-if="selectedFile" #alternative>
          <div class="flex min-h-0 flex-1 flex-col">
            <div class="shrink-0 border-b border-line-subtle px-pane py-1">
              <div class="flex items-center gap-1">
                <button
                  class="rounded-sm px-2 py-0.5 text-small text-faint hover:bg-hover hover:text-primary"
                  @click="selectedFile = null"
                >
                  {{ t('taskDetail.view.transcript') }}
                </button>
                <span class="rounded-sm bg-hover px-2 py-0.5 text-small font-medium">
                  {{ t('taskDetail.view.diff') }}
                </span>
              </div>
            </div>
            <div class="min-h-0 flex-1">
              <TaskDiff :team-id="task.team_id" :task-id="task.id" :path="selectedFile" />
            </div>
          </div>
        </template>
        <template #before>
          <section class="rounded-md bg-inset p-2">
            <p class="mb-1 text-small font-semibold tracking-wide text-faint uppercase">
              {{ t('taskDetail.sections.brief') }}
            </p>
            <p class="whitespace-pre-wrap">{{ task.brief || '—' }}</p>
            <AttachmentPicker :key="task.id" :model-value="task.attachments ?? []" :disabled="teams.busy" @update:model-value="teams.updateTask(task.id, { attachments: $event.map(entry => entry.reference) })" />
          </section>

          <section class="rounded-md bg-inset p-2">
            <p class="mb-1 text-small font-semibold tracking-wide text-faint uppercase">
              {{ t('tasks.writePaths.label') }}
            </p>
            <p v-if="task.write_paths?.length" class="font-mono text-small break-all whitespace-pre-wrap">{{ task.write_paths.join('\n') }}</p>
            <p v-else class="text-small text-faint">{{ t('tasks.writePaths.empty') }}</p>
          </section>

          <section v-if="dependencies.length" class="rounded-md bg-inset p-2">
            <p class="mb-1 text-small font-semibold tracking-wide text-faint uppercase">
              {{ t('taskDetail.sections.waitsFor') }}
            </p>
            <p v-for="entry in dependencies" :key="entry.id" class="flex items-baseline gap-2">
              <span v-if="entry.displayId" class="shrink-0 font-mono text-small text-faint">
                {{ entry.displayId }}
              </span>
              <span class="truncate" :class="entry.met ? 'text-faint line-through' : ''">
                {{ entry.title }}
              </span>
              <span class="ml-auto shrink-0 text-small text-faint">
                {{ statusLabel(entry.status) }}
              </span>
            </p>
            <p v-if="blockedBy.length && task.auto_start" class="mt-1 text-small text-faint">
              {{
                task.status === 'todo'
                  ? t('taskDetail.sections.startsAfterRelease')
                  : t('taskDetail.sections.startsOnItsOwn')
              }}
            </p>
          </section>

          <section v-if="writtenDocs.length" class="rounded-md bg-inset p-2">
            <p class="mb-1 text-small font-semibold tracking-wide text-faint uppercase">
              {{ t('taskDetail.sections.documentsWritten') }}
            </p>
            <RouterLink
              v-for="doc in writtenDocs"
              :key="doc.path"
              :to="{ name: 'docs', query: { path: doc.path } }"
              class="flex items-center gap-1.5 text-info hover:underline"
            >
              <FileText class="size-3 shrink-0" />
              <span class="truncate">{{ doc.title }}</span>
            </RouterLink>
          </section>

          <p v-if="task.error" class="rounded-md bg-danger-soft p-2 font-mono text-small text-danger">
            {{ task.error }}
          </p>
        </template>
        <template #message-extra="{ message }">
          <!-- What a review came with (§10.4). Drawn as pictures and links, unlike the
               remote image a model might write into its prose: these are files of this
               project, served by the Studio itself, put there by the reviewer. -->
          <ul
            v-if="attachmentsOf(message).length"
            class="mt-2 flex flex-wrap gap-2"
            :aria-label="t('taskDetail.review.attachments')"
          >
            <li v-for="entry in attachmentsOf(message)" :key="entry.name">
              <a
                :href="attachmentUrl(entry.name)"
                target="_blank"
                rel="noopener noreferrer"
                class="inline-flex max-w-72 items-center gap-1.5 rounded-md border border-line bg-surface px-2 py-1 text-small hover:bg-hover"
                :title="`${entry.name} · ${formatBytes(entry.size)}`"
              >
                <img
                  v-if="entry.image"
                  :src="attachmentUrl(entry.name)"
                  :alt="entry.name"
                  class="max-h-24 max-w-40 rounded-sm object-contain"
                />
                <FileText v-else class="size-3.5 shrink-0 text-faint" />
                <span class="truncate">{{ entry.name }}</span>
              </a>
            </li>
          </ul>
        </template>
        <template v-if="usage.totalTokens > 0" #usage>
          <TokenUsageChip
            class="ml-auto"
            :input-tokens="usage.inputTokens"
            :output-tokens="usage.outputTokens"
            :cached-tokens="usage.cacheReadTokens"
            :cost-usd="task.total_cost_usd"
            :cost-messages="task.messages"
          />
        </template>
        <template #controls>
          <!-- Next to Follow because both are settings for *watching* this run rather than
               fields of the card, and this is the row that is on screen at the moment the
               transcript stops mid-work. Bound through `:checked` and a handler rather than
               `v-model`: the value lives on the task, and the round trip is what confirms it
               — a model bound straight to the prop would tick itself before the server had
               agreed, and stay ticked if the patch were refused. -->
          <label
            v-if="canWaitOutLimit"
            class="flex cursor-pointer items-center gap-2"
            :title="t('taskDetail.limit.resumeOnResetHint')"
          >
            <input
              type="checkbox"
              class="accent-[var(--vxs-accent)]"
              :checked="task.resume_on_limit"
              :disabled="teams.busy"
              @change="setResumeOnLimit(($event.target as HTMLInputElement).checked)"
            />
            {{ t('taskDetail.limit.resumeOnReset') }}
          </label>
        </template>
      </ConversationPanel>

      <footer class="space-y-2 border-t border-line p-2">
        <!-- Fixed here rather than up in the transcript: the link is what
             the panel is open for while a session is live, and that is exactly when the
             transcript is scrolling it out of sight. A person's task has no session to hand
             over, so on those the block is absent rather than permanently disabled. -->
        <section v-if="!isHuman" class="rounded-md bg-inset p-2">
          <p class="mb-1 text-small font-semibold tracking-wide text-faint uppercase">
            {{ t('taskDetail.remote.title') }}
          </p>
          <!-- One row: the button first, because on a task with no link yet it is the only
               thing here that does anything, and the field beside it takes what is left. A
               session URL is longer than the ~340px rail whatever it is given, which is what
               the copy and open buttons next to it are for. -->
          <div class="flex items-center gap-2">
            <button
              v-if="!remoteLink"
              class="flex items-center gap-1.5 rounded-md border border-line px-2 py-1 hover:bg-hover disabled:opacity-50"
              :disabled="teams.busy || !canRemoteControl"
              :title="remoteHint"
              @click="teams.remoteControl(task.id, true)"
            >
              <Smartphone class="size-3.5" /> {{ t('taskDetail.remote.connect') }}
            </button>
            <button
              v-else
              class="flex items-center gap-1.5 rounded-md border border-line px-2 py-1 hover:bg-hover disabled:opacity-50"
              :disabled="teams.busy"
              :title="t('taskDetail.remote.disconnectHint')"
              @click="teams.remoteControl(task.id, false)"
            >
              <Unplug class="size-3.5" /> {{ t('taskDetail.remote.disconnect') }}
            </button>

            <div class="flex min-w-0 flex-1 items-center gap-1">
              <input
                ref="linkField"
                readonly
                :value="remoteLink ?? ''"
                :title="remoteLink ?? ''"
                :placeholder="t('taskDetail.remote.noLink')"
                class="min-w-0 flex-1 rounded-md border border-line bg-surface px-2 py-1 font-mono text-small outline-none focus:border-accent"
                :class="remoteLink ? '' : 'text-faint'"
                @focus="linkField?.select()"
              />
              <button
                class="grid size-6 shrink-0 place-items-center rounded-sm text-faint hover:bg-hover hover:text-primary disabled:opacity-40"
                :disabled="!remoteLink"
                :title="copied ? t('common.copied') : t('taskDetail.remote.copyLink')"
                @click="copyLink"
              >
                <Check v-if="copied" class="vxs-pop size-3.5 text-success" />
                <Copy v-else class="size-3.5" />
              </button>
              <a
                v-if="remoteLink"
                :href="remoteLink"
                target="_blank"
                rel="noopener noreferrer"
                class="grid size-6 shrink-0 place-items-center rounded-sm text-faint hover:bg-hover hover:text-primary"
                :title="t('taskDetail.remote.openLink')"
              >
                <ExternalLink class="size-3.5" />
              </a>
            </div>
          </div>
          <p class="mt-1 text-small text-faint">{{ remoteHint }}</p>
        </section>

        <!-- The review box takes files as well as words (§10.4): dropped onto it, pasted
             into it with Ctrl+V, or picked with the clip. Each is uploaded as it arrives and
             shown as a chip; the review names them when it is sent. The drop target is the
             whole box rather than the textarea alone, so a drop on a chip lands too. -->
        <!-- A person says how their work ended before submitting it (§10.4), as an agent
             does through finish_task: the backend requires it, and the queue is read by
             this colour first. -->
        <div v-if="canComplete" class="flex flex-wrap items-center gap-2">
          <span class="text-small text-faint">{{ t('resultType.label') }}</span>
          <SegmentedControl
            v-model="resultType"
            :options="resultChoices"
            :disabled="teams.busy"
          />
          <span
            class="inline-flex items-center gap-1.5 text-small"
            :style="{ color: resultTypeColor(resultType) }"
          >
            <span class="size-1.5 rounded-full" :style="{ background: resultTypeColor(resultType) }" />
            {{ resultTypeHint(resultType) }}
          </span>
        </div>
        <div v-if="canMessage" class="space-y-1">
          <div class="flex items-end gap-2">
            <textarea
              v-model="comment"
              rows="2"
              :placeholder="t('taskDetail.message.placeholder', { name: task.assignee?.name ?? '' })"
              class="block min-h-9 flex-1 resize-y rounded-md border border-line bg-inset px-2 py-1 outline-none focus:border-accent"
              @keydown="onMessageKeydown"
            />
            <button
              class="vxs-btn-primary grid size-9 shrink-0 place-items-center disabled:opacity-50"
              :disabled="messaging || !comment.trim()"
              :title="t('taskDetail.message.send')"
              @click="sendMessage"
            >
              <Send class="size-4" />
            </button>
          </div>
          <p class="text-small text-faint">{{ t('taskDetail.message.hint') }}</p>
        </div>
        <div
          v-if="canReview || canComplete"
          class="relative rounded-md"
          :class="dragging ? 'ring-2 ring-accent' : ''"
          @dragover="canReview && onDragOver($event)"
          @dragleave="dragging = false"
          @drop="canReview && onDrop($event)"
        >
          <textarea
            v-model="comment"
            rows="4"
            :placeholder="
              canReview
                ? t('taskDetail.review.commentPlaceholder')
                : t('taskDetail.review.summaryPlaceholder')
            "
            class="block w-full resize-y rounded-md border border-line bg-inset px-2 py-1 outline-none focus:border-accent"
            :class="canReview ? 'rounded-b-none border-b-0' : ''"
            @paste="canReview && onPaste($event)"
          />
          <div
            v-if="canReview"
            class="flex flex-wrap items-center gap-1.5 rounded-b-md border border-line bg-inset px-2 py-1"
          >
            <button
              class="inline-flex items-center gap-1 rounded-sm px-1.5 py-0.5 text-small text-faint hover:bg-hover hover:text-primary"
              :title="t('taskDetail.review.attachHint')"
              :disabled="teams.busy"
              @click="filePicker?.click()"
            >
              <Paperclip class="size-3.5" /> {{ t('taskDetail.review.attach') }}
            </button>
            <input
              ref="filePicker"
              type="file"
              multiple
              class="hidden"
              @change="pickFiles"
            />
            <span
              v-for="entry in attachments"
              :key="entry.name"
              class="inline-flex max-w-64 items-center gap-1 rounded-pill border border-line bg-surface px-2 py-0.5 text-small"
              :title="`${entry.name} · ${formatBytes(entry.size)}`"
            >
              <img
                v-if="entry.image"
                :src="attachmentUrl(entry.name)"
                :alt="entry.name"
                class="size-4 shrink-0 rounded-sm object-cover"
              />
              <FileText v-else class="size-3.5 shrink-0 text-faint" />
              <span class="truncate">{{ entry.name }}</span>
              <button
                class="grid size-4 shrink-0 place-items-center rounded-sm text-faint hover:bg-hover hover:text-primary"
                :title="t('taskDetail.review.remove', { name: entry.name })"
                :disabled="teams.busy"
                @click="removeAttachment(entry)"
              >
                <X class="size-3" />
              </button>
            </span>
            <span v-if="uploading" class="text-small text-faint">
              {{ t('taskDetail.review.uploading') }}
            </span>
            <span v-if="attachmentError" class="text-small text-danger">{{ attachmentError }}</span>
          </div>
          <div
            v-if="dragging"
            class="pointer-events-none absolute inset-0 grid place-items-center rounded-md bg-surface/80 text-small text-primary"
          >
            {{ t('taskDetail.review.dropHere') }}
          </div>
        </div>

        <div class="flex flex-wrap gap-2">
          <button
            v-if="canClaim"
            class="flex flex-1 items-center justify-center gap-1.5 vxs-btn-primary px-3 py-1.5 font-medium text-on-accent disabled:opacity-50"
            :disabled="teams.busy || blockedBy.length > 0"
            :title="
              blockedBy.length
                ? t('taskDetail.actions.waitingFor', {
                    tasks: blockedBy.map((entry) => entry.title).join(', '),
                  })
                : t('taskDetail.actions.claimHint')
            "
            @click="teams.act('claim', task.id, '', actorId)"
          >
            <Hand class="size-3.5" /> {{ t('taskDetail.actions.claim') }}
          </button>
          <button
            v-if="canComplete"
            class="flex flex-1 items-center justify-center gap-1.5 rounded-md bg-success px-3 py-1.5 font-medium text-on-accent disabled:opacity-50"
            :disabled="teams.busy"
            :title="
              manager
                ? t('taskDetail.actions.submitHint', { name: manager.name })
                : t('taskDetail.actions.markDoneHint')
            "
            @click="complete()"
          >
            <Send class="size-3.5" />
            {{ manager ? t('taskDetail.actions.submit') : t('taskDetail.actions.markDone') }}
          </button>

          <button
            v-if="canReview"
            class="flex flex-1 items-center justify-center gap-1.5 rounded-md bg-success px-3 py-1.5 font-medium text-on-accent disabled:opacity-50"
            :disabled="teams.busy"
            @click="review('approve')"
          >
            <Check class="size-3.5" /> {{ t('taskDetail.review.approve') }}
          </button>
          <button
            v-if="canReview"
            class="flex flex-1 items-center justify-center gap-1.5 rounded-md border border-line px-3 py-1.5 hover:bg-hover disabled:opacity-50"
            :disabled="teams.busy || !comment.trim()"
            @click="review('request-changes')"
          >
            <RotateCcw class="size-3.5" /> {{ t('taskDetail.review.requestChanges') }}
          </button>

          <button
            v-if="canRelease"
            class="flex flex-1 items-center justify-center gap-1.5 vxs-btn-primary px-3 py-1.5 font-medium text-on-accent disabled:opacity-50"
            :disabled="teams.busy"
            :title="releaseHint"
            @click="setStatus('pending')"
          >
            <ArrowRight class="size-3.5" /> {{ releaseLabel }}
          </button>

          <button
            v-if="canStart"
            class="flex flex-1 items-center justify-center gap-1.5 vxs-btn-primary px-3 py-1.5 font-medium text-on-accent disabled:opacity-50"
            :disabled="teams.busy || blockedBy.length > 0"
            :title="startHint"
            @click="teams.act('start', task.id)"
          >
            <Play class="size-3.5" />
            {{
              task.agent_session_id
                ? t('taskDetail.actions.resume')
                : t('taskDetail.actions.start')
            }}
          </button>

          <button
            v-if="task.is_running && !isCompressing"
            class="flex flex-1 items-center justify-center gap-1.5 rounded-md border border-line px-3 py-1.5 text-danger hover:bg-danger-soft"
            :disabled="teams.busy"
            @click="teams.act('cancel', task.id)"
          >
            <Ban class="size-3.5" /> {{ t('taskDetail.actions.cancelRun') }}
          </button>

          <button
            v-if="isCompressing"
            class="flex flex-1 items-center justify-center gap-1.5 rounded-md border border-line px-3 py-1.5 text-danger hover:bg-danger-soft disabled:opacity-50"
            :disabled="!task.is_compacting || teams.cancellingCompressionTaskId === task.id"
            :title="t('taskDetail.compression.cancelHint')"
            @click="teams.cancelCompression(task.id)"
          >
            <Ban class="size-3.5" /> {{ t('taskDetail.compression.cancel') }}
          </button>

          <button
            v-if="!isHuman"
            class="flex items-center justify-center gap-1.5 rounded-md border border-line px-3 py-1.5 hover:bg-hover disabled:opacity-50"
            :disabled="teams.busy || task.is_running || isCompressing || !hasCompressibleDialogue"
            :aria-busy="isCompressing"
            :title="compressionHint"
            @click="teams.act('compress', task.id)"
          >
            <LoaderCircle v-if="isCompressing" class="size-3.5 animate-spin" aria-hidden="true" />
            <Minimize2 v-else class="size-3.5" aria-hidden="true" />
            {{ isCompressing ? t('taskDetail.compression.busy') : t('taskDetail.compression.button') }}
          </button>

          <!-- Beside Delete because they are the same kind of act — throwing work away — and
               one step apart in how much: Reset keeps the card, Delete does not. -->
          <button
            v-if="!confirmingReset && !confirmingRemoval"
            class="grid shrink-0 place-items-center rounded-md border border-line px-2 py-1.5 text-faint hover:bg-hover hover:text-primary disabled:opacity-50"
            :disabled="task.is_running"
            :title="
              isCompressing
                ? t('taskDetail.compression.inProgress')
                : task.is_running
                ? t('taskDetail.actions.cancelRunFirst')
                : t('taskDetail.actions.resetHint')
            "
            @click="ask('reset')"
          >
            <ListRestart class="size-3.5" />
          </button>
          <button
            v-if="!confirmingReset && !confirmingRemoval"
            class="grid shrink-0 place-items-center rounded-md border border-line px-2 py-1.5 text-danger hover:bg-danger-soft disabled:opacity-50"
            :disabled="task.is_running"
            :title="
              isCompressing
                ? t('taskDetail.compression.inProgress')
                : task.is_running
                ? t('taskDetail.actions.cancelRunFirst')
                : t('taskDetail.actions.deleteHint')
            "
            @click="ask('removal')"
          >
            <Trash2 class="size-3.5" />
          </button>
        </div>

        <div v-if="confirmingReset" class="flex items-center gap-2">
          <p class="flex-1 text-small text-faint">
            {{ t('taskDetail.actions.resetConfirm') }}
          </p>
          <button
            class="rounded-md bg-danger px-3 py-1.5 font-medium text-on-accent disabled:opacity-50"
            :disabled="teams.busy"
            @click="reset"
          >
            {{ t('common.reset') }}
          </button>
          <button
            class="rounded-md border border-line px-3 py-1.5 hover:bg-hover"
            @click="confirmingReset = false"
          >
            {{ t('taskDetail.actions.keep') }}
          </button>
        </div>

        <div v-if="confirmingRemoval" class="flex items-center gap-2">
          <p class="flex-1 text-small text-faint">
            {{ t('taskDetail.actions.deleteConfirm') }}
          </p>
          <button
            class="rounded-md bg-danger px-3 py-1.5 font-medium text-on-accent disabled:opacity-50"
            :disabled="teams.busy"
            @click="remove"
          >
            {{ t('common.delete') }}
          </button>
          <button
            class="rounded-md border border-line px-3 py-1.5 hover:bg-hover"
            @click="confirmingRemoval = false"
          >
            {{ t('taskDetail.actions.keep') }}
          </button>
        </div>
      </footer>
    </div>

    <!-- Full height beside the whole dialog, not inside the transcript: the session's
         status — its sub-agents, skills, MCP servers, the files it declared and the files it
         touched — is a property
         of the task rather than of the conversation, and a reviewer reads the two together. -->
    <aside v-if="!embedded" class="w-[298px] shrink-0 border-l border-line">
      <SessionStatus :messages="task.messages" :subagents="delegated" :write-paths="task.write_paths ?? []">
        <TaskChanges
          :team-id="task.team_id"
          :task-id="task.id"
          :revision="changesRevision"
          :selected="selectedFile"
          @select="selectedFile = $event"
        />
      </SessionStatus>
    </aside>
  </div>
</template>
