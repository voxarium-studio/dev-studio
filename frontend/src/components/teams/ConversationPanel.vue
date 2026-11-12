<script setup lang="ts">
/**
 * The one transcript widget (§10.4, §10.9, §10.11): the task dialog, the Producer page's task
 * cards, ordinary chats and the Producer's conversation all draw their feed here, so a run
 * reads the same wherever it is opened — the same verbosity dial, the same folded thinking
 * and "Tools used" blocks, the same tail line.
 *
 * `chat` marks a conversation rather than a task: its live line follows only the current
 * turn, a typing indicator shows while the member answers, and the user's own messages carry
 * the context they were sent with.
 */
import { useResizeObserver } from '@vueuse/core'
import {
  ArrowDown,
  Bot,
  Brain,
  Clock,
  FilePen,
  FileText,
  GitCommitHorizontal,
  GraduationCap,
  Pencil,
  RefreshCw,
  Wrench,
} from 'lucide-vue-next'
import { computed, nextTick, ref, watch } from 'vue'

import ChatUserMessage from '@/components/chat/ChatUserMessage.vue'
import EffortSolverResult from '@/components/chat/EffortSolverResult.vue'
import ProducerCompaction from '@/components/chat/ProducerCompaction.vue'
import TranscriptFold from '@/components/chat/TranscriptFold.vue'
import MarkdownText from '@/components/ui/MarkdownText.vue'
import ModelEffortSlider from '@/components/ui/ModelEffortSlider.vue'
import { t } from '@/i18n'
import { toolName, toolSubject } from '@/lib/activity'
import { spawnEffortSolver } from '@/lib/effort-solver'
import { formatTime, statusColor, statusLabel } from '@/lib/status'
import { resultTypeColor, resultTypeHint, resultTypeLabel, resultTypeOf } from '@/lib/task-result'
import type { TranscriptBlock, TranscriptMessage } from '@/lib/transcript'
import {
  PROSE_KINDS,
  VERBOSITY_HINTS,
  VERBOSITY_LABELS,
  VERBOSITY_LEVELS,
  bareToolName,
  commitText,
  editStat,
  editedText,
  historyReason,
  isDelegatedWork,
  isFileEdit,
  isQueued,
  isSkillCall,
  isSubagentSpawn,
  liveLine,
  resultsByCall,
  tailLine,
  transcriptBlocks,
} from '@/lib/transcript'
import { useVerbosity } from '@/lib/transcript-verbosity'
import { useTeamsStore } from '@/stores/teams'

const props = defineProps<{
  conversationId: string | null
  messages: TranscriptMessage[]
  emptyText: string
  embedded?: boolean
  taskStatus?: string
  /** A chat with a member, by name, and whether it is answering right now (§10.9). */
  chat?: { name: string; running: boolean }
}>()
const teams = useTeamsStore()
const transcript = ref<HTMLElement | null>(null)
const verbosity = useVerbosity()
const blocks = computed(() => transcriptBlocks(props.messages, verbosity.value))
/** Every call's result, whatever the level shows — a spawn row reads its evaluation from it. */
const results = computed(() => resultsByCall(props.messages))
const live = computed(() => {
  let start = 0
  if (props.chat) {
    // Only the current turn of a chat has live activity (§10.9).
    if (!props.chat.running) return { activity: '', seq: 0 }
    props.messages.forEach((message, index) => { if (message.role === 'user') start = index + 1 })
  }
  return liveLine(start ? props.messages.slice(start) : props.messages, verbosity.value)
})

// ---- the folds

/**
 * The folds the reader turned the other way from where the level starts them (§10.4):
 * Default starts every fold collapsed, Verbose — which filters nothing — starts them open.
 * Kept by block key, its first message's id, so a block that grows while the reader has it
 * one way stays that way as new calls land in it. Turning the dial or opening another
 * conversation starts every fold where the level puts it again.
 */
const flipped = ref(new Set<string>())

function isOpen(key: string): boolean {
  return (verbosity.value === 'verbose') !== flipped.value.has(key)
}

function toggle(key: string): void {
  if (flipped.value.has(key)) flipped.value.delete(key)
  else flipped.value.add(key)
}

watch([() => props.conversationId, verbosity], () => flipped.value.clear())

/** The distinct tools a block called, in order — what the folded line says besides its count. */
function toolNames(block: Extract<TranscriptBlock, { type: 'tools' }>): string {
  const names = new Set<string>()
  for (const entry of block.entries) {
    if (entry.message.kind === 'tool_use') names.add(bareToolName(entry.message) || toolName(entry.message))
  }
  return [...names].join(', ')
}

/**
 * A tool's result, at Verbose: the payload as it came — a `*` is a character here and
 * column alignment carries meaning — capped in height so one long listing does not push
 * the rest of the block off the screen.
 */
const RESULT_CLASS =
  'max-h-48 overflow-auto whitespace-pre-wrap break-words rounded-sm bg-inset px-2 py-1 font-mono text-small'

/** Whether every call in a block started a subagent — its fold then wears the robot too. */
function onlySpawns(block: Extract<TranscriptBlock, { type: 'tools' }>): boolean {
  const own = block.entries.filter((entry) => entry.message.kind === 'tool_use')
  return own.length > 0 && own.every((entry) => isSubagentSpawn(entry.message))
}

/**
 * What Effort Solver decided for each `spawn_subagent` call (§10.3), by the call's message id,
 * read from the call's result. `pending` while the run is live and nothing has answered — the
 * evaluation runs before the tool returns, so that is exactly when it is being computed. A
 * call whose result is an error or carries no scores (written before they were returned) has
 * no entry, and its row draws nothing.
 */
const evaluations = computed(() => {
  const live = props.chat ? props.chat.running : props.taskStatus === 'in_progress'
  const found = new Map<string, ReturnType<typeof spawnEffortSolver> | 'pending'>()
  for (const message of props.messages) {
    if (!isSubagentSpawn(message)) continue
    const id = message.data?.id
    const result = typeof id === 'string' ? results.value.get(id) : undefined
    if (!result) {
      if (live) found.set(message.id, 'pending')
      continue
    }
    const evaluation = result.data?.is_error ? null : spawnEffortSolver(result.text)
    if (evaluation) found.set(message.id, evaluation)
  }
  return found
})

function lastTime(block: Extract<TranscriptBlock, { type: 'tools' }>): string {
  const last = block.entries.at(-1)
  return last ? formatTime((last.results.at(-1) ?? last.message).created_at) : ''
}

function actorName(message: TranscriptMessage): string {
  const actor = message.data?.actor_id
  if (typeof actor === 'string') return teams.memberById.get(actor)?.name ?? actor
  return t('taskDetail.you')
}

/** What a status entry records: the transition, and why it happened when there is a why. */
function transition(message: TranscriptMessage): {
  from: string | null
  to: string
  reason: string
} {
  return {
    from: typeof message.data?.from === 'string' ? message.data.from : null,
    to: typeof message.data?.to === 'string' ? message.data.to : props.taskStatus ?? '',
    reason: typeof message.data?.reason === 'string' ? message.data.reason : '',
  }
}

const follow = ref(true)
const atBottom = ref(true)

/** Within a line of the end counts as the end — a scroll position rarely lands exactly. */
function measureBottom(): void {
  const element = transcript.value
  if (!element) return
  atBottom.value = element.scrollHeight - element.scrollTop - element.clientHeight < 32
}

async function scrollToLatest(): Promise<void> {
  await nextTick()
  const element = transcript.value
  if (!element) return
  element.scrollTop = element.scrollHeight
  atBottom.value = true
}

watch(
  [() => props.messages.length, () => live.value.seq, () => props.chat?.running],
  () => {
    if (follow.value && atBottom.value) void scrollToLatest()
  },
)

// Switching it back on is an instruction to catch up, not just to follow from here.
watch(follow, (enabled) => {
  if (enabled) void scrollToLatest()
})

/**
 * Turning the dial is a re-read of the same feed, and every row above the reader changes
 * height when it does — so someone who was at the end is put back there rather than left
 * wherever that many pixels now lands. Someone who had scrolled up keeps their place.
 */
watch(verbosity, () => {
  if (atBottom.value) void scrollToLatest()
})
watch([() => props.conversationId, transcript], () => void scrollToLatest(), { immediate: true })
// Expanding a task's delegated list or the chat composer also resizes the viewport.
useResizeObserver(transcript, () => {
  if (follow.value && atBottom.value) void scrollToLatest()
})
</script>

<template>
  <slot v-if="$slots.alternative" name="alternative" />
  <div v-else ref="transcript" data-conversation-transcript
    class="flex-1 space-y-2 overflow-auto p-pane" :class="embedded ? 'min-h-32' : 'min-h-0'"
    @scroll.passive="measureBottom">
    <slot name="before" />
    <template v-for="block in blocks" :key="block.key">
      <!-- Thinking: a dashed fold, collapsed at Default and open at Verbose (§10.4). -->
      <TranscriptFold
        v-if="block.type === 'thinking'"
        :open="isOpen(block.key)"
        :label="t('taskDetail.transcript.thinking')"
        @toggle="toggle(block.key)"
      >
        <template #icon><Brain class="size-3.5 shrink-0" /></template>
        <template #meta>{{ formatTime(block.message.created_at) }}</template>
        <MarkdownText :text="block.message.text" />
      </TranscriptFold>

      <!-- Consecutive tool calls, one fold: what the agent did between two things it said.
           A call reads as a line — name and the argument that identifies it — and one that
           wrote a file carries its size; at Verbose each call's result sits under it. -->
      <TranscriptFold
        v-else-if="block.type === 'tools'"
        :open="isOpen(block.key)"
        :label="t(block.studio ? 'taskDetail.transcript.studioToolsUsed' : 'taskDetail.transcript.toolsUsed', { count: block.calls })"
        :studio="block.studio"
        @toggle="toggle(block.key)"
      >
        <template #icon>
          <Bot v-if="block.studio && onlySpawns(block)" class="size-3.5 shrink-0 text-violet-400" />
          <Wrench v-else class="size-3.5 shrink-0" :class="block.studio ? 'text-violet-400' : ''" />
        </template>
        <template #summary>{{ toolNames(block) }}</template>
        <template #meta>{{ lastTime(block) }}</template>
        <div class="space-y-1">
          <template v-for="entry in block.entries" :key="entry.key">
            <p
              v-if="entry.message.kind === 'tool_use'"
              class="flex items-baseline gap-2 text-faint"
              :class="isDelegatedWork(entry.message) ? 'pl-4' : ''"
              :title="toolSubject(entry.message)"
            >
              <FilePen v-if="isFileEdit(entry.message)" class="size-3 shrink-0 translate-y-0.5" />
              <Bot
                v-else-if="isSubagentSpawn(entry.message)"
                class="size-3 shrink-0 translate-y-0.5 text-violet-400"
                data-subagent-spawn
              />
              <GraduationCap
                v-else-if="isSkillCall(entry.message)"
                class="size-3 shrink-0 translate-y-0.5"
                :style="{ color: 'var(--vxs-nav-skills)' }"
              />
              <Wrench v-else class="size-3 shrink-0 translate-y-0.5" />
              <span class="shrink-0 font-mono text-primary">{{ toolName(entry.message) }}</span>
              <span class="min-w-0 truncate">{{ toolSubject(entry.message) }}</span>
              <span v-if="editStat(entry.message)" class="shrink-0 font-mono">
                <span class="text-success">+{{ editStat(entry.message)!.insertions }}</span>
                <span class="ml-1 text-danger">−{{ editStat(entry.message)!.deletions }}</span>
              </span>
              <span class="ml-auto shrink-0">{{ formatTime(entry.message.created_at) }}</span>
            </p>
            <pre
              v-else-if="entry.message.kind === 'tool_result'"
              :class="[RESULT_CLASS, entry.message.data?.is_error ? 'text-danger' : 'text-muted']"
            >{{ entry.message.text }}</pre>
            <!-- A system frame between two calls, or a delegated agent's own words. -->
            <p
              v-else
              class="flex items-baseline gap-2 text-faint"
              :class="isDelegatedWork(entry.message) ? 'pl-4' : ''"
              :title="tailLine(entry.message)"
            >
              <span class="shrink-0 font-mono">{{ entry.message.kind }}</span>
              <span class="min-w-0 truncate">{{ tailLine(entry.message) }}</span>
              <span class="ml-auto shrink-0">{{ formatTime(entry.message.created_at) }}</span>
            </p>
            <!-- A subagent's launch carries the Effort Solver evaluation that chose its
                 effort (§10.3), right under the call, at every level. -->
            <p
              v-if="evaluations.get(entry.message.id) === 'pending'"
              class="vxs-task-activity ml-5 text-faint"
            >
              {{ t('effortSolver.evaluating') }}
            </p>
            <EffortSolverResult
              v-else-if="evaluations.get(entry.message.id)"
              :data="evaluations.get(entry.message.id) as Record<string, unknown>"
              class="ml-5 max-w-sm"
              data-spawn-evaluation
            />
            <pre
              v-for="result in entry.results"
              :key="result.id"
              :class="[
                RESULT_CLASS,
                result.data?.is_error ? 'text-danger' : 'text-muted',
                isDelegatedWork(entry.message) ? 'ml-4' : '',
              ]"
            >{{ result.text }}</pre>
          </template>
        </div>
      </TranscriptFold>
      <!-- History reads as a timeline rather than as a message: it has no author
           speaking, and giving it the same card as one would bury the transcript in
           bookkeeping. -->
      <p
        v-else-if="block.message.kind === 'status'"
        class="flex items-center gap-2 px-1 text-small text-faint"
      >
        <span
          class="size-1.5 shrink-0 rounded-full"
          :style="{ background: statusColor(transition(block.message).to) }"
        />
        <span v-if="transition(block.message).from">
          {{ statusLabel(transition(block.message).from!) }} →
        </span>
        <span class="font-medium text-primary">
          {{ statusLabel(transition(block.message).to) }}
        </span>
        <span v-if="!transition(block.message).from">
          · {{ t('taskDetail.history.created') }}
        </span>
        <span v-else-if="transition(block.message).reason">
          · {{ historyReason(transition(block.message).reason) }}
        </span>
        <span class="truncate">· {{ actorName(block.message) }}</span>
        <span class="ml-auto shrink-0">{{ formatTime(block.message.created_at) }}</span>
      </p>

      <p
        v-else-if="block.message.kind === 'edited'"
        class="flex items-center gap-2 px-1 text-small text-faint"
      >
        <Pencil class="size-3 shrink-0" />
        <span class="truncate">
          {{ editedText(block.message) }} · {{ actorName(block.message) }}
        </span>
        <span class="ml-auto shrink-0">{{ formatTime(block.message.created_at) }}</span>
      </p>

      <!-- The Commit button landing (§10.4): history like a status move, since it is
           the task's work leaving the working copy, and worth a line at every level. -->
      <p
        v-else-if="block.message.kind === 'commit'"
        class="flex items-center gap-2 px-1 text-small text-faint"
        :title="typeof block.message.data?.title === 'string' ? block.message.data.title : ''"
      >
        <GitCommitHorizontal class="size-3 shrink-0 text-success" />
        <span class="truncate">{{ commitText(block.message) }}</span>
        <span class="ml-auto shrink-0">{{ formatTime(block.message.created_at) }}</span>
      </p>

      <!-- The Studio resuming a stalled session, or declining to. Timeline rather than
           card for the same reason as the rest: nobody is speaking. -->
      <p
        v-else-if="block.message.kind === 'continued'"
        class="flex items-baseline gap-2 px-1 text-small text-faint"
      >
        <RefreshCw class="size-3 shrink-0 translate-y-0.5" />
        <span class="min-w-0">{{ block.message.text }}</span>
        <span class="ml-auto shrink-0">{{ formatTime(block.message.created_at) }}</span>
      </p>

      <!-- Preparation and incomplete disk evidence remain visible at every level (§10.4). -->
      <p
        v-else-if="block.message.kind === 'file_observation_status' || block.message.kind === 'file_observation_error'"
        class="flex items-baseline gap-2 px-1 text-small"
        :class="block.message.kind === 'file_observation_error' ? 'text-warning' : 'text-faint'"
      >
        <FileText class="size-3 shrink-0 translate-y-0.5" />
        <span class="min-w-0 break-words">{{ block.message.text }}</span>
        <span class="ml-auto shrink-0">{{ formatTime(block.message.created_at) }}</span>
      </p>

      <EffortSolverResult v-else-if="block.message.kind === 'effort_solver'" :data="block.message.data" />
      <ProducerCompaction v-else-if="chat && block.message.kind === 'producer_compacted'" :message="block.message" />

      <!-- A summary that came with a result type (§10.4) wears its colour: the edge and
           a chip in the result's green, blue or red, with the words left as they are.
           Level 3 of the styling rules — the colour is a token resolved per entry. -->
      <article
        v-else
        class="rounded-md border p-2"
        :class="
          block.message.role === 'user'
            ? 'border-accent-border bg-accent-soft'
            : ['cancelled', 'error'].includes(block.message.kind)
              ? 'border-danger bg-danger-soft'
              : 'border-line-subtle bg-elevated'
        "
        :style="
          resultTypeOf(block.message)
            ? {
                borderColor: resultTypeColor(resultTypeOf(block.message)!),
                boxShadow: `inset 3px 0 0 ${resultTypeColor(resultTypeOf(block.message)!)}`,
              }
            : undefined
        "
      >
        <p class="mb-1 flex items-baseline gap-2 text-small text-faint">
          <span class="font-medium">
            {{
              block.message.role === 'user'
                ? t('taskDetail.you')
                : (teams.memberById.get(block.message.member_id)?.name ??
                  chat?.name ?? block.message.member_id)
            }}
          </span>
          <span class="font-mono">{{ ['context_summary', 'compressed summary'].includes(block.message.kind) ? t('taskDetail.compression.summary') : block.message.kind }}</span>
          <span
            v-if="resultTypeOf(block.message)"
            class="inline-flex items-center gap-1 font-medium"
            :style="{ color: resultTypeColor(resultTypeOf(block.message)!) }"
            :title="resultTypeHint(resultTypeOf(block.message)!)"
          >
            <span
              class="size-1.5 rounded-full"
              :style="{ background: resultTypeColor(resultTypeOf(block.message)!) }"
            />
            {{ resultTypeLabel(resultTypeOf(block.message)!) }}
          </span>
          <!-- Sent while the agent was working and not handed over yet (§10.8). -->
          <span
            v-if="isQueued(block.message)"
            class="inline-flex items-center gap-1 rounded-pill bg-inset px-2 leading-5"
            :title="t('chat.transcript.queuedHint')"
          >
            <Clock class="size-3" /> {{ t('chat.transcript.queued') }}
          </span>
          <span class="ml-auto">{{ formatTime(block.message.created_at) }}</span>
        </p>
        <!-- §10.3: structured model frames use the same effort display as the header. -->
        <div
          v-if="block.message.kind === 'model' && typeof block.message.data?.effort === 'string'"
          class="flex flex-wrap items-center gap-2"
        >
          <span v-if="typeof block.message.data.model === 'string'" class="font-mono text-small">
            {{ block.message.data.model }}
          </span>
          <ModelEffortSlider
            :model-value="block.message.data.effort"
            :levels="teams.options?.effort_levels"
            readonly
          />
          <span v-if="block.message.data.source === 'task'" class="text-small text-faint">
            {{ t('taskDetail.runSettings.overridden') }}
          </span>
        </div>
        <ChatUserMessage v-else-if="chat && block.message.role === 'user'" :message="block.message" />
        <p v-else-if="chat && block.message.kind === 'cancelled'" class="text-danger">{{ t('chat.transcript.stopped') }}</p>
        <MarkdownText v-else-if="PROSE_KINDS.has(block.message.kind)" :text="block.message.text" />
        <p v-else class="whitespace-pre-wrap">{{ block.message.text }}</p>
        <slot name="message-extra" :message="block.message" />
      </article>
    </template>
    <p
      v-if="live.activity || chat?.running || $slots.usage"
      class="flex items-center gap-2 px-1 pl-4 text-small text-faint"
    >
      <span v-if="chat?.running" class="vxs-chat-typing shrink-0" :aria-label="t('chat.answering')"><i /><i /><i /></span>
      <span
        v-if="live.activity"
        :key="live.seq"
        class="vxs-task-activity min-w-0 truncate font-mono"
        :title="live.activity"
      >
        {{ live.activity }}
      </span>
      <slot name="usage" />
    </p>
    <p v-if="!blocks.length && !live.activity && !chat?.running && !$slots.usage" class="text-faint">{{ emptyText }}</p>
  </div>

  <!-- Outside the scroll area on purpose: "follow" is wanted exactly when the user has
       scrolled away from the end, which is where a control living at the end of the
       transcript cannot be reached. -->
  <div
    class="shrink-0 border-t border-line-subtle px-pane py-1 text-small text-faint"
  >
    <div class="flex flex-wrap items-center gap-x-4 gap-y-1">
      <!-- A dial rather than a switch: there were always three readings of a run — what
           happened, what was done, and everything — and only two settings for them. -->
      <label
        class="flex cursor-pointer items-center gap-2"
        :title="t(VERBOSITY_HINTS[verbosity])"
      >
        {{ t('taskDetail.verbosity.label') }}
        <select
          v-model="verbosity"
          class="cursor-pointer rounded-sm border border-line bg-inset px-1 py-0.5 outline-none focus:border-accent"
        >
          <option v-for="level in VERBOSITY_LEVELS" :key="level" :value="level">
            {{ t(VERBOSITY_LABELS[level]) }}
          </option>
        </select>
      </label>
      <label class="flex cursor-pointer items-center gap-2" :title="t('taskDetail.follow.hint')">
        <input v-model="follow" type="checkbox" class="accent-[var(--vxs-accent)]" />
        {{ t('taskDetail.follow.label') }}
      </label>
      <slot name="controls" />
      <button
        v-if="!atBottom"
        class="ml-auto flex items-center gap-1 rounded-sm px-1 hover:bg-hover hover:text-primary"
        :title="t('taskDetail.follow.latestHint')"
        @click="scrollToLatest()"
      >
        <ArrowDown class="size-3" /> {{ t('taskDetail.follow.latest') }}
      </button>
    </div>
  </div>
</template>
