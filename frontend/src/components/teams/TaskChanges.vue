<script setup lang="ts">
/**
 * What one task changed, down the right-hand side of its dialog (§10.4, §10.5) — and the
 * commit drafted for it.
 *
 * The column answers one of two questions and says which, because they are not the same
 * question and a reviewer acts on the answer. With an isolated checkout it is the task's
 * own diff against the commit it was branched from — committed work included, which is
 * what stops an agent that commits its own output from reading as having done nothing.
 * Without one it combines tool paths with disk observations, labelled per row. A shared
 * working copy cannot establish the author of an observed write; those files stay out of
 * automatic commit drafts. Counts exist only for tool-only paths and describe requests.
 *
 * A row is the file's name alone; the directory is in its tooltip. Two files of one name
 * in one task are rare, and a second line under every row cost the column half its rows.
 *
 * Studio data under `.voxarium_studio/` has its own group (§10.4). Task scratch files are
 * listed separately under Temp, from the directory listing rather than the change history.
 * The remaining files are split
 * on the directory the task's **session** worked
 * in (`workdir`) rather than on the project: a run started one level up edits the siblings
 * as part of its job, and a run started in the project that reached past it did something
 * worth a heading of its own. Commit eligibility still follows the session directory.
 *
 * The Commit section under the list is the draft the backend wrote when the task reached
 * review: the board's title (not editable — it is the task's id and name), a description a
 * cheap model wrote (editable, since it is a draft), and the files. The button commits
 * through whatever version control the project is under; nothing here names git.
 *
 * The file list folds under its heading like the Status column's other lists, and starts folded
 * (`lib/fold-state.ts`); the count, the refresh button, a load error and the Commit section stay
 * in view either way.
 *
 * Selection is owned by the parent: picking a file swaps what the main pane shows, and
 * that is a decision about the dialog rather than about this list.
 */
import { ChevronDown, FileDiff, GitCommitHorizontal, Loader2, RefreshCw, Sparkles } from 'lucide-vue-next'
import { computed, onBeforeUnmount, ref, watch } from 'vue'

import { api } from '@/api/client'
import type { TaskChanges, TaskCommitProposal, VcsCommitResult, VcsFileChange } from '@/api/types'
import { t } from '@/i18n'
import { rememberedFold } from '@/lib/fold-state'
import { taskTempChanged } from '@/lib/task-temp'
import { useSessionStore } from '@/stores/session'
import { VCS_MARKS } from '@/stores/vcs'
import { onEvent } from '@/ws/connection'

const props = defineProps<{
  teamId: string
  taskId: string
  /** Bumped by the parent when the task has moved on and the list is worth re-reading. */
  revision?: number
  selected: string | null
}>()
const emit = defineEmits<{ select: [string | null] }>()
const session = useSessionStore()

const changes = ref<TaskChanges | null>(null)
const open = rememberedFold('files')
const loading = ref(false)
const error = ref<string | null>(null)

interface Row extends VcsFileChange {
  name: string
  studio: boolean
  temp: boolean
  mark: (typeof VCS_MARKS)[keyof typeof VCS_MARKS]
}

function fileRow(file: VcsFileChange, temp = false): Row {
  const path = file.path.replace(/\\/g, '/').replace(/^(\.\/)+/, '')
  const cut = path.lastIndexOf('/')
  return {
    ...file,
    // Paths are relative to the reported root, not the session's workdir (§10.4).
    studio: path.startsWith('.voxarium_studio/'),
    temp,
    name: cut === -1 ? path : path.slice(cut + 1),
    mark: VCS_MARKS[file.kind] ?? VCS_MARKS.unknown,
  }
}

const rows = computed<Row[]>(() => [
  ...(changes.value?.files ?? []).map((file) => fileRow(file)),
  ...(changes.value?.temp_files ?? []).map((file) => fileRow(file, true)),
])

/** The groups, in the order they are drawn. All always exist — the heading is the
 *  statement "nothing reached outside", which an absent section cannot make. */
const groups = computed(() => [
  {
    key: 'project',
    label: t('tasks.changes.group.project'),
    rows: rows.value.filter((row) => !row.temp && !row.studio && !row.outside),
  },
  {
    key: 'studio',
    label: t('tasks.changes.group.studio'),
    rows: rows.value.filter((row) => !row.temp && row.studio),
  },
  {
    key: 'temp',
    label: t('tasks.changes.group.temp'),
    rows: rows.value.filter((row) => row.temp),
  },
  {
    key: 'outside',
    label: t('tasks.changes.group.outside'),
    rows: rows.value.filter((row) => !row.temp && !row.studio && row.outside),
  },
])

/** Summed over what the backend actually counted; `null` when nothing was counted at all —
 *  the untracked half of a workspace, or a transcript written before the counting existed. */
const totals = computed(() => {
  const counted = (changes.value?.files ?? []).filter(
    (file) => file.insertions != null || file.deletions != null,
  )
  if (!counted.length) return null
  return {
    insertions: counted.reduce((sum, file) => sum + (file.insertions ?? 0), 0),
    deletions: counted.reduce((sum, file) => sum + (file.deletions ?? 0), 0),
  }
})

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    changes.value = await api.taskChanges(props.teamId, props.taskId)
    // A file that is no longer in the list cannot stay open in the pane beside it.
    if (props.selected && !rows.value.some((file) => file.path === props.selected)) {
      emit('select', null)
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.taskId, props.revision],
  () => void load(),
  { immediate: true },
)

function countClass(file: VcsFileChange): string {
  return file.binary ? 'text-faint' : ''
}

// ---------------------------------------------------------------- the commit

const proposal = ref<TaskCommitProposal | null>(null)
const proposalError = ref<string | null>(null)
/**
 * The description as the reviewer is editing it. Seeded from each draft that arrives and
 * replaced by the next one: a draft is rewritten when the task comes back to review, and
 * what the model wrote about the new work beats a paragraph typed about the old.
 */
const message = ref('')
const committing = ref(false)
const commitError = ref<string | null>(null)
const committed = ref<VcsCommitResult | null>(null)

async function loadProposal(): Promise<void> {
  proposalError.value = null
  try {
    const next = await api.taskCommit(props.teamId, props.taskId)
    const fresh =
      !proposal.value ||
      proposal.value.task_id !== next.task_id ||
      proposal.value.generated_at !== next.generated_at
    proposal.value = next
    if (fresh) {
      message.value = next.message
      commitError.value = null
      committed.value = null
    }
  } catch (cause) {
    proposalError.value = cause instanceof Error ? cause.message : String(cause)
  }
}

watch(
  () => props.taskId,
  () => {
    proposal.value = null
    message.value = ''
    committed.value = null
    commitError.value = null
    void loadProposal()
  },
  { immediate: true },
)

// The draft is written in the background after the status moves; the event is what says
// it landed, and the list beside it is re-read for the same reason the parent bumps
// `revision` — a status change is when the answer changes.
const stopListening = onEvent('team.task.commit_changed', (payload) => {
  if (payload.task_id !== props.taskId) return
  void loadProposal()
  if (!payload.generating) void load()
})
onBeforeUnmount(stopListening)

// Disk observations and task scratch files arrive independently of final status (§10.4).
// Coalesce file events; transcript output alone never starts a workspace diff.
let fileRefresh: ReturnType<typeof setTimeout> | undefined
function refreshFilesSoon(): void {
  clearTimeout(fileRefresh)
  const taskId = props.taskId
  fileRefresh = setTimeout(() => {
    if (props.taskId === taskId) void load()
  }, 300)
}
const stopObservations = onEvent('team.member.message', (payload) => {
  if (payload.task_id !== props.taskId || !payload.kind.startsWith('file_observation')) return
  refreshFilesSoon()
})
const stopTempFiles = onEvent('explorer.file_changed', (payload) => {
  if (taskTempChanged(changes.value?.temp_root, session.project?.root, payload)) refreshFilesSoon()
})
onBeforeUnmount(() => {
  stopObservations()
  stopTempFiles()
  clearTimeout(fileRefresh)
})

const canCommit = computed(
  () =>
    !!proposal.value &&
    proposal.value.vcs.backend !== null &&
    proposal.value.vcs.available &&
    proposal.value.files.length > 0 &&
    !proposal.value.generating &&
    !committing.value,
)

async function commit(): Promise<void> {
  if (!proposal.value || !canCommit.value) return
  committing.value = true
  commitError.value = null
  try {
    committed.value = await api.commitTask(props.teamId, props.taskId, {
      message: message.value,
      files: proposal.value.files,
    })
    await Promise.all([loadProposal(), load()])
  } catch (cause) {
    commitError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    committing.value = false
  }
}

async function regenerate(): Promise<void> {
  if (!proposal.value || proposal.value.generating) return
  proposalError.value = null
  try {
    proposal.value = await api.regenerateTaskCommit(props.teamId, props.taskId)
  } catch (cause) {
    proposalError.value = cause instanceof Error ? cause.message : String(cause)
  }
}

/** One line under the heading saying why the button is or is not live. */
const commitNote = computed(() => {
  const draft = proposal.value
  if (!draft) return ''
  if (draft.vcs.backend === null) return t('tasks.commit.noVcs')
  if (!draft.vcs.available) {
    return t('tasks.commit.toolUnusable', { name: draft.vcs.name ?? '', reason: draft.vcs.reason })
  }
  if (draft.generating) return t('tasks.commit.generating')
  if (!draft.generated_at) return t('tasks.commit.waitingForReview')
  if (!draft.files.length) return t('tasks.commit.noFiles')
  if (!draft.enabled) return t('tasks.commit.disabled')
  if (draft.error) return draft.error
  return t('tasks.commit.generatedBy', { model: draft.model })
})
</script>

<template>
  <section class="flex h-full min-h-0 w-full flex-col bg-canvas">
    <!-- A subsection of the Status column (§10.4), worded like the ones above it. -->
    <header class="flex shrink-0 items-center gap-1 px-3 pt-2 pb-1">
      <button
        type="button"
        class="flex min-w-0 flex-1 items-center gap-1.5 rounded-sm py-0.5 text-small font-medium text-faint hover:bg-hover hover:text-primary"
        :aria-expanded="open"
        data-files-toggle
        @click="open = !open"
      >
        <FileDiff class="size-3 shrink-0" />
        <span class="truncate">
          {{ changes?.source === 'tools' ? t('tasks.changes.touched') : t('tasks.changes.changed') }}
        </span>
        <span v-if="changes" class="font-mono">{{ rows.length }}</span>
        <ChevronDown class="vxs-chevron ml-auto size-3.5 shrink-0" :class="open ? 'rotate-180' : ''" />
      </button>
      <button
        class="grid size-5 shrink-0 place-items-center rounded-sm text-faint hover:bg-hover hover:text-primary"
        :title="t('tasks.changes.refresh')"
        :disabled="loading"
        @click="load"
      >
        <Loader2 v-if="loading" class="size-3.5 animate-spin" />
        <RefreshCw v-else class="size-3.5" />
      </button>
    </header>

    <!-- Folded, the list gives its height up and the Commit section stays at the bottom:
         the draft is where a reviewer ends, wherever the list was left. -->
    <div class="min-h-0 flex-1 overflow-y-auto py-1">
      <p v-if="error" class="px-3 py-2 text-small text-danger">{{ error }}</p>
      <p v-else-if="changes?.error" class="px-3 py-2 text-small text-warning">
        {{ changes.error }}
      </p>

      <template v-if="open">
        <template v-for="group in groups" :key="group.key">
          <!-- All headings always, so an empty "Outside project" says the task stayed home
               rather than leaving the reader to infer it from a heading that is not there. -->
          <p
            class="flex items-baseline gap-1.5 px-3 pt-2 pb-0.5 text-small font-medium text-faint"
            :title="
              group.key === 'outside'
                ? t('tasks.changes.group.outsideHint', { workdir: changes?.workdir ?? '' })
                : group.key === 'studio'
                  ? `${changes?.root ?? ''}/.voxarium_studio/`
                  : group.key === 'temp'
                    ? t('tasks.changes.group.tempHint', { path: changes?.temp_root ?? '' })
                    : changes?.workdir
            "
          >
            <span>{{ group.label }}</span>
            <span class="font-mono">{{ group.rows.length }}</span>
          </p>

          <button
            v-for="row in group.rows"
            :key="row.path"
            class="flex w-full items-baseline gap-2 px-3 py-1 text-left hover:bg-hover"
            :class="selected === row.path ? 'bg-active' : ''"
            :title="
              !row.temp && changes?.source === 'workspace'
                ? t('tasks.changes.rowTitle', { kind: row.mark.label, path: row.path })
                : row.path
            "
            @click="emit('select', selected === row.path ? null : row.path)"
          >
            <!-- Only where a kind was actually established. The transcript source knows a file
                 was written and nothing more, and a `?` down the whole column reads as a
                 question about each file rather than about the source. -->
            <span
              v-if="!row.temp && changes?.source === 'workspace'"
              class="w-2 shrink-0 font-mono text-small"
              :class="row.mark.class"
            >
              {{ row.mark.letter }}
            </span>
            <!-- The name alone; the path is the row's tooltip. -->
            <span
              class="min-w-0 flex-1 truncate"
              :class="row.kind === 'deleted' ? 'text-faint line-through' : ''"
            >
              {{ row.name }}
            </span>
            <span
              v-if="row.evidence === 'observed' || row.evidence === 'tools_and_observed'"
              class="shrink-0 text-small text-faint"
              :title="t(row.evidence === 'observed' ? 'tasks.changes.observedHint' : 'tasks.changes.bothHint')"
            >
              {{ t(row.evidence === 'observed' ? 'tasks.changes.observed' : 'tasks.changes.both') }}
            </span>
            <span class="shrink-0 font-mono text-small" :class="countClass(row)">
              <template v-if="row.binary">{{ t('tasks.changes.binary') }}</template>
              <template v-else>
                <span v-if="row.insertions" class="text-success">+{{ row.insertions }}</span>
                <span v-if="row.deletions" class="ml-1 text-danger">−{{ row.deletions }}</span>
              </template>
            </span>
          </button>

          <p
            v-if="!group.rows.length && !loading && !error"
            class="px-3 pb-1 text-small text-faint"
          >
            <template v-if="group.key !== 'project' || rows.length">
              {{ t('tasks.changes.group.empty') }}
            </template>
            <template v-else-if="changes?.source === 'tools'">
              {{ t('tasks.changes.noneWritten') }}
            </template>
            <template v-else>{{ t('tasks.changes.noneChanged') }}</template>
          </p>
        </template>

        <p
          v-if="rows.some((row) => row.evidence === 'observed')"
          class="border-t border-line-subtle px-3 py-2 text-small text-faint"
        >
          {{ t('tasks.changes.observedHint') }}
        </p>
        <p v-for="warning in (changes?.observation_errors ?? []).filter((warning) => !warning.startsWith('File observation incomplete:'))" :key="warning" class="px-3 py-2 text-small text-warning">
          {{ warning }}
        </p>
        <p v-for="warning in changes?.temp_errors ?? []" :key="`temp:${warning}`" class="px-3 py-2 text-small text-warning">
          {{ warning }}
        </p>
        <p v-if="changes?.reason && !changes.reason.startsWith('This task ran in the project itself rather than its own checkout')" class="border-t border-line-subtle px-3 py-2 text-small text-faint">
          {{ changes.reason }}
        </p>

        <p
          v-if="changes?.branch || totals"
          class="border-t border-line-subtle px-3 py-1.5 text-small text-faint"
        >
          <span v-if="changes?.branch" class="flex items-center gap-1.5">
            <FileDiff class="size-3 shrink-0 text-info" />
            <span class="truncate font-mono text-info" :title="changes.root">
              {{ changes.branch }}
            </span>
          </span>
          <!-- "isolated checkout" is a statement about where the figures were measured, so
               the transcript's summed requests do not carry it. -->
          <span v-if="totals" class="block">
            <span class="text-success">+{{ totals.insertions }}</span>
            <span class="ml-1 text-danger">−{{ totals.deletions }}</span>
            <span v-if="changes?.source === 'workspace'" class="ml-1">
              {{ t('tasks.changes.isolated') }}
            </span>
          </span>
        </p>
      </template>
    </div>

    <!-- The draft, pinned under the list so it stays in reach however long the list is. -->
    <section
      class="flex shrink-0 flex-col gap-2 border-t border-line px-3 py-2"
      :aria-label="t('tasks.commit.heading')"
    >
      <header class="flex items-center gap-1.5">
        <GitCommitHorizontal class="size-3.5 shrink-0 text-faint" />
        <span class="flex-1 truncate text-small font-semibold tracking-wide text-faint uppercase">
          {{ t('tasks.commit.heading') }}
          <span v-if="proposal?.files.length" class="ml-1 font-medium text-primary">
            {{ proposal.files.length }}
          </span>
        </span>
        <button
          class="grid size-5 shrink-0 place-items-center rounded-sm text-faint hover:bg-hover hover:text-primary disabled:opacity-50"
          :title="t('tasks.commit.regenerate')"
          :disabled="!proposal || proposal.generating"
          @click="regenerate"
        >
          <Loader2 v-if="proposal?.generating" class="size-3.5 animate-spin" />
          <Sparkles v-else class="size-3.5" />
        </button>
      </header>

      <p v-if="proposalError" class="text-small text-danger">{{ proposalError }}</p>

      <template v-if="proposal">
        <label class="block">
          <span class="block text-small text-faint">{{ t('tasks.commit.titleLabel') }}</span>
          <span class="block truncate font-mono text-small text-primary" :title="proposal.title">
            {{ proposal.title }}
          </span>
        </label>

        <label class="block">
          <span class="block text-small text-faint">{{ t('tasks.commit.messageLabel') }}</span>
          <textarea
            v-model="message"
            rows="4"
            class="mt-0.5 w-full resize-y rounded-md border border-line bg-inset px-2 py-1 text-small disabled:opacity-50"
            :placeholder="t('tasks.commit.messagePlaceholder')"
            :disabled="proposal.generating || committing"
          />
        </label>

        <p
          class="text-small"
          :class="proposal.error && !proposal.generating ? 'text-warning' : 'text-faint'"
        >
          {{ commitNote }}
        </p>

        <p v-if="commitError" class="text-small text-danger">{{ commitError }}</p>
        <p v-else-if="committed && !committed.committed" class="text-small text-faint">
          {{ t('tasks.commit.nothingLeft') }}
        </p>
        <p v-else-if="proposal.revision" class="truncate text-small text-success">
          {{ t('tasks.commit.committed', { revision: proposal.revision }) }}
        </p>
        <!-- Said beside either outcome: a file the draft listed and the workspace no longer
             reports as changed stays out of the commit, and a reviewer should not have to
             diff the two lists to notice. -->
        <p
          v-if="committed?.skipped?.length"
          class="text-small text-faint"
          :title="committed.skipped.join('\n')"
        >
          {{ t('tasks.commit.skipped', { count: committed.skipped.length }) }}
        </p>

        <button
          class="flex items-center justify-center gap-1.5 vxs-btn-primary px-3 py-1.5 text-small font-medium text-on-accent disabled:opacity-50"
          :disabled="!canCommit"
          @click="commit"
        >
          <Loader2 v-if="committing" class="size-3.5 animate-spin" />
          <GitCommitHorizontal v-else class="size-3.5" />
          {{
            committing
              ? t('tasks.commit.committing')
              : proposal.files.length
                ? `${t('tasks.commit.commit')} · ${t('tasks.commit.files', { count: proposal.files.length })}`
                : t('tasks.commit.commit')
          }}
        </button>
      </template>
    </section>
  </section>
</template>
