<script setup lang="ts">
/**
 * The sub-agents and workflows a session started (§10.3): how many are running and how the
 * rest ended on one line, the list itself one click away.
 *
 * Folded out of the messages rather than shown as transcript rows: the lifecycle frames are
 * `system` messages, which every level below Verbose hides, and even shown they are one row
 * per progress report rather than one row per piece of work. What a reader wants is the
 * current list — which is also the thing that explains a task sitting In progress after its
 * agent has apparently stopped talking.
 *
 * It lives in the task's Status column (§10.4) — a property of the run, like the files it
 * touched — and in the header of a Producer page card, which has no column to put it in.
 */
import { Bot, ChevronDown } from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'

import ModelEffortSlider from '@/components/ui/ModelEffortSlider.vue'
import TokenUsageChip from '@/components/ui/TokenUsageChip.vue'
import { t, type MessageKey } from '@/i18n'
import { statusColor } from '@/lib/status'
import { running as stillRunning, type Subagent } from '@/lib/subagents'

const props = defineProps<{ entries: Subagent[] }>()

const delegatedRunning = computed(() => stillRunning(props.entries))
/** Work the run is actually held open for — a background shell is shown and is not that. */
const heldFor = computed(() => delegatedRunning.value.filter((entry) => entry.holdsRun).length)

/**
 * How the finished half ended, for the line that shows while the list is folded away —
 * "3 done · 1 failed". A failure is named rather than counted into "done": it is usually why
 * the task is worth a second look, and folded this line is all there is.
 */
const tally = computed(() => {
  let done = 0
  let failed = 0
  let stopped = 0
  for (const entry of props.entries) {
    if (entry.status === 'completed') done += 1
    else if (entry.status === 'failed') failed += 1
    else if (entry.status === 'stopped') stopped += 1
  }
  const parts: string[] = []
  if (done) parts.push(t('taskDetail.delegated.done', { count: done }))
  if (failed) parts.push(t('taskDetail.delegated.failed', { count: failed }))
  if (stopped) parts.push(t('taskDetail.delegated.stopped', { count: stopped }))
  return parts.join(' · ')
})

/** Why the run is still open, on the folded line's tooltip so the answer needs no click. */
const hint = computed(() =>
  heldFor.value
    ? t('taskDetail.delegated.heldOpen', { count: heldFor.value })
    : t('taskDetail.delegated.hint'),
)

/**
 * Folded or open, remembered like the verbosity dial and for the same reason: the preference
 * is the reader's rather than the task's. Folded is where a first read starts, so a run with
 * twenty sub-agents does not push everything under it out of sight.
 */
const STORAGE_KEY = 'voxarium_studio.transcript.delegated'
const open = ref(readOpen())

function readOpen(): boolean {
  try {
    return window.localStorage.getItem(STORAGE_KEY) === 'open'
  } catch {
    return false
  }
}

watch(open, (value) => {
  try {
    window.localStorage.setItem(STORAGE_KEY, value ? 'open' : 'closed')
  } catch {
    // Unremembered, it still folds.
  }
})

/** The shared status colours, so a running sub-agent is the same amber as a running task. */
function color(entry: Subagent): string {
  return statusColor(entry.status === 'completed' ? 'done' : entry.status)
}

const ENDINGS: Record<Exclude<Subagent['status'], 'running'>, MessageKey> = {
  completed: 'taskDetail.delegated.status.completed',
  failed: 'taskDetail.delegated.status.failed',
  stopped: 'taskDetail.delegated.status.stopped',
}

/**
 * The right-hand end of a row: what it is doing now, or how it ended. Counted rather than
 * timed — a duration would have to tick, re-rendering every row each second.
 */
function note(entry: Subagent): string {
  const parts: string[] = []
  if (entry.status === 'running') {
    if (entry.lastTool) parts.push(entry.lastTool)
  } else {
    parts.push(t(ENDINGS[entry.status]))
  }
  if (entry.toolUses) parts.push(t('taskDetail.delegated.calls', { count: entry.toolUses }))
  return parts.join(' · ')
}
</script>

<template>
  <section data-subagent-summary>
    <button
      class="flex w-full items-center gap-1.5 rounded-sm py-0.5 text-small text-faint hover:bg-hover hover:text-primary"
      :aria-expanded="open"
      :title="hint"
      @click="open = !open"
    >
      <Bot class="size-3 shrink-0" />
      <span class="truncate font-medium">{{ t('taskDetail.delegated.title') }}</span>
      <span
        v-if="delegatedRunning.length"
        class="vxs-subagent-running flex shrink-0 items-center gap-1"
        :style="{ color: statusColor('in_progress') }"
      >
        <span class="size-1.5 rounded-full bg-current" />
        {{ t('taskDetail.delegated.running', { count: delegatedRunning.length }) }}
      </span>
      <span v-if="delegatedRunning.length && tally" class="shrink-0">·</span>
      <span v-if="tally" class="shrink-0">{{ tally }}</span>
      <span v-if="!entries.length" class="shrink-0">{{ t('taskDetail.status.noSubagents') }}</span>
      <ChevronDown
        v-if="entries.length"
        class="vxs-chevron ml-auto size-3.5 shrink-0"
        :class="open ? 'rotate-180' : ''"
      />
    </button>

    <template v-if="open && entries.length">
      <!-- Its own scroller: five agents in flight is a number this project has actually seen. -->
      <div class="mt-0.5 max-h-40 overflow-auto pr-1">
        <p
          v-for="entry in entries"
          :key="entry.id"
          class="flex flex-wrap items-center gap-x-2 gap-y-1 py-0.5"
          :title="entry.summary || entry.description"
        >
          <span
            class="size-1.5 shrink-0 translate-y-[-1px] rounded-full"
            :class="entry.status === 'running' ? 'vxs-subagent-running' : ''"
            :style="{ background: color(entry) }"
          />
          <span class="shrink-0 text-small text-faint">{{ entry.typeLabel }}</span>
          <span
            v-if="entry.backend"
            class="shrink-0 text-small text-faint"
            :title="entry.model || entry.backend"
          >{{ entry.backend }}</span>
          <span class="min-w-0 flex-1 truncate" :class="entry.status === 'running' ? '' : 'text-faint'">
            {{ entry.description }}
          </span>
          <!-- One unit, so the fixed-size effort control never drops to a line of its own:
               in the narrow Status column the pair takes the second line and the note wraps
               beside the control; where there is room it stays on the first. -->
          <span class="ml-auto flex max-w-full min-w-0 items-center gap-2">
            <span class="min-w-0 break-words text-right font-mono text-small text-faint">
              {{ note(entry) }}
            </span>
            <!-- §10.11: the effort Effort Solver chose at spawn, on the control every other
                 run's effort is read from (§10.3). A runtime's own sub-agent reports none. -->
            <ModelEffortSlider
              v-if="entry.effort"
              :model-value="entry.effort"
              :label="t('taskDetail.delegated.effort')"
              readonly
            />
          </span>
          <TokenUsageChip
            :input-tokens="entry.inputTokens"
            :output-tokens="entry.outputTokens"
            :cached-tokens="entry.cacheReadTokens"
            :cost-usd="entry.costUsd"
          />
        </p>
      </div>
      <!-- Outside the scroller: it explains the whole list, so it must not scroll out of it. -->
      <p v-if="heldFor" class="mt-1 text-small text-faint">
        {{ t('taskDetail.delegated.heldOpen', { count: heldFor }) }}
      </p>
    </template>
  </section>
</template>
