<script setup lang="ts">
/**
 * Chat with a team member — a floating popup or the two-pane Chats page (§10.9).
 *
 * Teleported to `<body>` like every floating thing here, so that no ancestor's overflow or
 * stacking context can position or clip it. Below the dialogs (`z-40` against their `z-50`),
 * so a task opened on the board still covers the chat; above the rail and the page headers.
 *
 * What the member is told with each message is decided here, because only the component
 * sees all three sources: the route (the section, the Explorer's file), the docs store (the
 * open document and its live heading) and the page's text selection, watched through
 * `selectionchange` and kept as *the last non-empty selection made outside the popup* — a
 * click into the composer collapses the page's selection, and dropping the context at that
 * exact moment would make it impossible to select something and then ask about it.
 */
import {
  ArrowLeft,
  ChevronDown,
  MessageCircle,
  Maximize2,
  Plus,
  Send,
  Square,
  Trash2,
  X,
} from 'lucide-vue-next'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import type { ChatContext, TeamMember } from '@/api/types'
import MemberMark from '@/components/teams/MemberMark.vue'
import MemberSelect from '@/components/teams/MemberSelect.vue'
import { t } from '@/i18n'
import {
  DEFAULT_CHAT_SIZE,
  dragChatSize,
  fitChatSize,
  readChatSize,
  type ChatSize,
  type ResizeEdge,
} from '@/lib/chat-size'
import { basename, describeLocation, pageLabelOnScreen, selectedText } from '@/lib/user-context'
import AttachmentPicker from '@/components/ui/AttachmentPicker.vue'
import type { UploadedAttachment } from '@/api/client'
import { draftKey, readDraft, useChatStore, writeDraft } from '@/stores/chat'
import { useDocsStore } from '@/stores/docs'
import { useTeamsStore } from '@/stores/teams'

import ConversationPanel from '@/components/teams/ConversationPanel.vue'
import SessionStatus from '@/components/teams/SessionStatus.vue'
import { subagents } from '@/lib/subagents'
import ChatList from './ChatList.vue'
import ChatHeaderChips from './ChatHeaderChips.vue'
import ProducerControls from './ProducerControls.vue'
import ProducerTaskStatus from './ProducerTaskStatus.vue'

const chat = useChatStore()
const teams = useTeamsStore()
const docs = useDocsStore()
const route = useRoute()
// One mounted widget moves into the page so drafts and pending uploads survive (§10.9).
const fullscreen = computed(() => route.name === 'chats')
const producerPage = computed(() => route.name === 'producer')
const docked = computed(() => fullscreen.value || producerPage.value)
const visible = computed(() => docked.value || chat.open)
watch(docked, value => chat.pageVisible = value, { immediate: true, flush: 'sync' })

const root = ref<HTMLElement | null>(null)
const composer = ref<HTMLTextAreaElement | null>(null)

// ---- who

const agents = computed(() => teams.members.filter((member) => member.kind === 'agent'))

function memberOf(id: string | null): TeamMember | null {
  return id ? (teams.memberById.get(id) ?? teams.teams.flatMap(team => team.members).find(member => member.id === id) ?? null) : null
}

const activeMember = computed(() => memberOf(chat.activeChat?.member_id ?? null))
const readOnlyHistory = computed(() => chat.view === 'chat' && activeMember.value?.kind === 'human')

/**
 * The task dialog's Status column, on the Chats page only (§10.9): the popup is too narrow
 * for a third column and the Producer page has its own task column. A chat has no declared
 * write files and no commit, so only the session half — sub-agents, skills, MCP servers —
 * is shown, read from the transcript exactly as a task's is.
 */
const showStatus = computed(() => fullscreen.value && chat.view === 'chat' && Boolean(chat.activeChatId))
const chatSubagents = computed(() => (showStatus.value ? subagents(chat.activeMessages) : []))
const newMember = computed(() => memberOf(chat.newMemberId))

const producerName = computed(() => (chat.view === 'new' ? newMember.value : activeMember.value)?.name ?? t('chat.theMember'))

const newMemberModel = computed({
  get: () => chat.newMemberId,
  set: (value: string | null) => {
    chat.newMemberId = value
  },
})

// The first agent is the default addressee, so the new-chat screen never opens on nobody.
watch(
  agents,
  (list) => {
    if (!list.length) return
    if (!chat.newMemberId || !list.some((member) => member.id === chat.newMemberId)) {
      chat.newMemberId = list[0]!.id
    }
  },
  { immediate: true },
)

// ---- the size

/**
 * Two values, as `SidePane` keeps them: the size the user chose, which is what is stored,
 * and the size that fits this window, which is what is drawn. Storing the fitted one would
 * file the choice down to the smallest window the popup was ever opened in.
 */
const SIZE_KEY = 'voxarium_studio.chat.size'
const chosenSize = ref<ChatSize>(
  readChatSize(
    (() => {
      try {
        return window.localStorage.getItem(SIZE_KEY)
      } catch {
        return null
      }
    })(),
  ),
)
const viewport = ref({ width: window.innerWidth, height: window.innerHeight })
const size = computed(() =>
  fitChatSize(chosenSize.value, viewport.value.width, viewport.value.height),
)
const panelStyle = computed(() => ({
  width: `${size.value.width}px`,
  height: `${size.value.height}px`,
}))
const resizing = ref(false)

function onViewportResize(): void {
  viewport.value = { width: window.innerWidth, height: window.innerHeight }
}

let pendingSize: ReturnType<typeof setTimeout> | undefined
watch(chosenSize, (value) => {
  clearTimeout(pendingSize)
  pendingSize = setTimeout(() => {
    try {
      window.localStorage.setItem(SIZE_KEY, JSON.stringify(value))
    } catch {
      /* remembered or not, it still resizes */
    }
  }, 250)
})

/** A drag on the top edge, the left edge or the corner between them — the popup is
 * anchored bottom-right, so that is where it can grow from. */
function startResize(edge: ResizeEdge, event: PointerEvent): void {
  if (event.button !== 0) return
  event.preventDefault()
  const origin = { x: event.clientX, y: event.clientY }
  const start = { ...size.value }
  resizing.value = true
  const move = (moved: PointerEvent) => {
    chosenSize.value = dragChatSize(
      start,
      edge,
      moved.clientX - origin.x,
      moved.clientY - origin.y,
      window.innerWidth,
      window.innerHeight,
    )
  }
  const stop = () => {
    resizing.value = false
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', stop)
    window.removeEventListener('pointercancel', stop)
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', stop)
  window.addEventListener('pointercancel', stop)
}

function resetSize(): void {
  chosenSize.value = { ...DEFAULT_CHAT_SIZE }
}

// ---- the draft

const draft = ref('')
const attachmentDrafts = ref<Record<string, UploadedAttachment[]>>({})
const uploadingAttachments = ref(false)
const attachmentDraftKey = computed(() => currentDraftKey.value ?? 'new')
const attached = computed({
  get: () => attachmentDrafts.value[attachmentDraftKey.value] ?? [],
  set: (value: UploadedAttachment[]) => { attachmentDrafts.value[attachmentDraftKey.value] = value },
})
const currentDraftKey = computed(() => draftKey(chat.view, chat.activeChatId))
let pendingSave: ReturnType<typeof setTimeout> | undefined

watch(
  currentDraftKey,
  (key, previous) => {
    clearTimeout(pendingSave)
    if (previous && previous !== key) writeDraft(previous, draft.value)
    draft.value = key ? readDraft(key) : ''
  },
  { immediate: true },
)

watch(draft, (text) => {
  const key = currentDraftKey.value
  if (!key) return
  clearTimeout(pendingSave)
  pendingSave = setTimeout(() => writeDraft(key, text), 250)
})

const draftRows = computed(() => Math.min(6, Math.max(1, draft.value.split('\n').length)))

/** Addressed to whoever the message would go to — the picked member, or the open chat's. */
const composerPlaceholder = computed(() => {
  const member = chat.view === 'new' ? newMember.value : activeMember.value
  return member
    ? t('chat.composer.placeholder', { name: member.name })
    : t('chat.composer.placeholderNobody')
})

// ---- where the user is

const location = computed<Omit<ChatContext, 'selection'>>(() =>
  describeLocation(route, {
    docPath: docs.open?.path ?? null,
    docTitle: docs.draftTitle || docs.open?.title || null,
  }),
)

const placeChip = computed(() => {
  const here = location.value
  if (here.document) return { label: basename(here.document), title: here.document }
  if (here.file) return { label: basename(here.file), title: here.file }
  return null
})

function onSelectionChange(): void {
  const text = selectedText(document.getSelection(), (node) =>
    Boolean(root.value && node instanceof Node && root.value.contains(node)),
  )
  if (text) {
    chat.setSelection(text)
    return
  }
  // An empty selection made *on the page* is the user letting go of it; one that merely
  // collapsed because they clicked into the popup keeps what they had selected.
  if (!(root.value && root.value.contains(document.activeElement))) chat.clearSelection()
}

// A different page is a different context; a selection from the last one would mislead.
watch(() => route.fullPath, () => chat.clearSelection())

function context(): ChatContext {
  return { ...location.value, selection: chat.selection || null }
}

// ---- sending

// A message sent while the member is answering is not refused (§10.8): it is queued and
// reaches them at the next step, so the composer stays live and Stop sits beside Send.
const running = computed(() => chat.view === 'chat' && chat.activeChat?.is_running === true)
const canSend = computed(
  () =>
    (draft.value.trim().length > 0 || attached.value.length > 0) &&
    !uploadingAttachments.value &&
    !chat.busy &&
    !readOnlyHistory.value &&
    !(chat.view === 'chat' && chat.activeChat?.producer_paused) &&
    (chat.view === 'chat' ? Boolean(chat.activeChatId) : Boolean(chat.newMemberId)),
)

async function submit(): Promise<void> {
  if (!canSend.value) return
  const text = draft.value
  const key = currentDraftKey.value
  const attachmentKey = attachmentDraftKey.value
  if (await chat.send(text, context(), attached.value.map(entry => entry.reference), producerPage.value)) {
    attachmentDrafts.value[attachmentKey] = []
    if (key === currentDraftKey.value) {
      if (draft.value === text) {
        draft.value = ''
        if (key) writeDraft(key, '')
      }
      await nextTick()
      if (key === currentDraftKey.value) composer.value?.focus()
    } else if (key && readDraft(key) === text) {
      writeDraft(key, '')
    }
  }
}

function onComposerKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  void submit()
}

// ---- deleting

async function deleteActive(): Promise<void> {
  const id = chat.activeChatId
  if (!id) return
  await chat.remove(id)
}

// ---- focus and lifecycle

function focusComposer(): void {
  void nextTick(() => composer.value?.focus({ preventScroll: true }))
}

watch(
  () => [visible.value, chat.view] as const,
  ([open, view]) => {
    if (open && view !== 'list') focusComposer()
  },
)

function onEscape(): void {
  if (!docked.value) chat.hide()
}

// The roster is needed only once the popup shows — a name on a row, the picker, a mark —
// so it is fetched on opening rather than on every page load, where the board's own view
// would be loading the same team a second time.
watch(
  () => visible.value,
  (open) => {
    if (open && !teams.members.length && !teams.busy) void teams.load()
  },
  { immediate: true },
)

onMounted(() => {
  void chat.load()
  document.addEventListener('selectionchange', onSelectionChange)
  window.addEventListener('resize', onViewportResize)
  if (visible.value && chat.view !== 'list') focusComposer()
})

onBeforeUnmount(() => {
  chat.pageVisible = false
  document.removeEventListener('selectionchange', onSelectionChange)
  window.removeEventListener('resize', onViewportResize)
  clearTimeout(pendingSave)
  clearTimeout(pendingSize)
  const key = currentDraftKey.value
  if (key) writeDraft(key, draft.value)
})

</script>

<template>
  <Teleport defer :to="producerPage ? '#producer-chat' : fullscreen ? '#chats-page' : 'body'">
    <div
      ref="root"
      class="vxs-chat"
      :class="docked ? 'h-full min-h-0' : 'pointer-events-none fixed right-5 bottom-5 z-40 flex flex-col items-end gap-3'"
      data-chat-widget
    >
      <!-- `vxs-chat-panel` (`base.css`, the motion section): the popup folds out of the round
           button under it and back into it, on transitions, so a second click on the button
           while it is still moving turns it round. -->
      <Transition :name="docked ? undefined : 'vxs-chat-panel'">
      <section
        v-if="visible"
        class="pointer-events-auto relative flex bg-surface"
        :class="[docked ? 'h-full min-h-0' : 'vxs-card flex-col rounded-xl border border-card shadow-card', resizing ? 'select-none' : '']"
        :style="docked ? undefined : panelStyle"
        :role="docked ? 'region' : 'dialog'"
        :aria-label="t('chat.label')"
        @keydown.esc.stop.prevent="onEscape"
      >
        <!-- Resize from the free side: the left edge, the top edge and the corner between
             them. The popup is anchored bottom-right, so a drag up and left grows it; a
             double-click on the corner goes back to the default size. The handles sit
             outside the clipped content below, reaching a few pixels past the border: the
             rounded corner would otherwise cut the corner handle out of hit-testing. -->
        <div
          v-if="!docked"
          class="absolute -left-1 top-3 bottom-3 z-10 w-2 cursor-ew-resize"
          :title="t('chat.resize.edge')"
          @pointerdown="startResize('left', $event)"
        />
        <div
          v-if="!docked"
          class="absolute -top-1 right-3 left-3 z-10 h-2 cursor-ns-resize"
          :title="t('chat.resize.edge')"
          @pointerdown="startResize('top', $event)"
        />
        <div
          v-if="!docked"
          class="absolute -top-1 -left-1 z-10 size-5 cursor-nwse-resize"
          :title="t('chat.resize.corner')"
          @pointerdown="startResize('corner', $event)"
          @dblclick="resetSize"
        />
        <aside
          v-if="fullscreen"
          class="flex min-h-0 w-[clamp(12rem,30%,20rem)] shrink-0 flex-col border-r border-line bg-canvas max-sm:w-full"
          :class="chat.view !== 'list' ? 'max-sm:hidden' : ''"
          :aria-label="t('chat.allChats')"
        >
          <header class="flex h-12 shrink-0 items-center gap-2 border-b border-line px-3">
            <MessageCircle class="size-4 shrink-0 text-faint" />
            <h1 class="min-w-0 flex-1 truncate font-medium">{{ t('chat.chats') }}</h1>
            <button v-if="agents.length" class="flex h-8 items-center gap-1 rounded-md px-2 text-small hover:bg-hover" @click="chat.startNew()">
              <Plus class="size-4" />
              <span>{{ t('chat.newChat') }}</span>
            </button>
          </header>
          <ChatList />
        </aside>
        <div class="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden rounded-[inherit]" :class="fullscreen && chat.view === 'list' ? 'max-sm:hidden' : ''">
        <!-- Header: where we are in the popup, and the way out. -->
        <header v-if="!producerPage" class="shrink-0 border-b border-line">
          <div class="flex h-12 items-center gap-2 px-2">
            <button
              v-if="chat.view !== 'list'"
              :class="fullscreen ? 'sm:hidden' : ''"
              class="grid size-8 shrink-0 place-items-center rounded-md hover:bg-hover"
              :title="t('chat.allChats')"
              @click="chat.showList()"
            >
              <ArrowLeft class="size-4" />
            </button>

            <template v-if="chat.view === 'chat' && activeMember">
              <MemberMark :member="activeMember" variant="badge" :corner="false" class="shrink-0" />
              <div class="min-w-0 flex-1 leading-tight">
                <p class="truncate font-medium">{{ activeMember.name }}</p>
                <p class="truncate text-small text-muted">
                  {{ running ? t('chat.answeringNow') : activeMember.role_title }}
                </p>
              </div>
              <button
                class="grid size-8 shrink-0 place-items-center rounded-md hover:bg-hover"
                :title="t('chat.delete.action')"
                :disabled="chat.busy"
                :aria-label="t('chat.delete.action')"
                @click="deleteActive"
              >
                <Trash2 class="size-4" />
              </button>
            </template>

            <template v-else-if="chat.view === 'chat'">
              <p class="min-w-0 flex-1 truncate font-medium">{{ t('chat.title') }}</p>
            </template>

            <template v-else-if="chat.view === 'new'">
              <p class="min-w-0 flex-1 truncate font-medium">{{ t('chat.newChat') }}</p>
            </template>

            <template v-else>
              <MessageCircle class="ml-1 size-4 shrink-0 text-faint" />
              <p class="min-w-0 flex-1 truncate font-medium">{{ t('chat.chats') }}</p>
              <button
                v-if="!docked && agents.length"
                class="flex h-8 shrink-0 items-center gap-1 rounded-md px-2 text-small hover:bg-hover"
                :title="t('chat.startChat')"
                @click="chat.startNew()"
              >
                <Plus class="size-4" />
                <span>{{ t('chat.newChat') }}</span>
              </button>
            </template>

            <RouterLink v-if="!docked" to="/chats" class="grid size-8 shrink-0 place-items-center rounded-md hover:bg-hover" :title="t('chat.openPage')" :aria-label="t('chat.openPage')">
              <Maximize2 class="size-4" />
            </RouterLink>
            <button
              v-if="!docked"
              class="grid size-8 shrink-0 place-items-center rounded-md hover:bg-hover"
              :title="t('chat.minimise')"
              @click="chat.hide()"
            >
              <ChevronDown class="size-4" />
            </button>
          </div>
          <ChatHeaderChips v-if="chat.view === 'chat' && chat.activeChat && activeMember?.kind === 'agent'"
            :chat="chat.activeChat" :member="activeMember" :messages="chat.activeMessages" :levels="teams.options?.effort_levels" />
        </header>

          <ProducerControls v-if="!producerPage" />
          <ChatList v-if="!docked && chat.view === 'list'" />
        <div v-else-if="chat.view === 'list'" class="flex min-h-0 flex-1 items-center justify-center p-6 text-center text-faint">
          <p>{{ t('chat.chooseConversation') }}</p>
        </div>

        <!-- The Producer page opens on its own composer, so its "new" screen is the empty
             transcript rather than a member picker. -->
        <ConversationPanel
          v-else-if="producerPage"
          :conversation-id="chat.view === 'chat' ? chat.activeChatId : null"
          :messages="chat.view === 'chat' ? chat.activeMessages : []"
          :chat="{ name: producerName, running }"
          :empty-text="t(chat.view === 'new' ? 'chat.new.hint' : 'chat.transcript.empty', { name: producerName })"
        />

        <!-- A chat that does not exist yet: who, then the first message. -->
        <div v-else-if="chat.view === 'new'" class="flex min-h-0 flex-1 flex-col">
          <div class="border-b border-line px-3 py-3">
            <MemberSelect
              v-model="newMemberModel"
              :members="agents"
              :label="t('chat.new.talkTo')"
              :placeholder="t('chat.new.pickMember')"
            />
          </div>
          <p class="m-auto max-w-[18rem] px-4 text-center text-faint">
            {{ t('chat.new.hint', { name: newMember?.name ?? t('chat.theMember') }) }}
          </p>
        </div>

        <!-- The conversation, in the same widget as a task's transcript and the Producer's
             (§10.9): one verbosity dial, the same folded thinking and tool blocks. -->
        <ConversationPanel
          v-else
          :conversation-id="chat.activeChatId"
          :messages="chat.activeMessages"
          :chat="{ name: producerName, running }"
          :empty-text="t('chat.transcript.empty', { name: producerName })"
        />

        <ProducerTaskStatus v-if="!producerPage" />

        <!-- The composer, under the list too — but only where something can be sent. -->
        <p v-if="readOnlyHistory" class="shrink-0 border-t border-line p-3 text-small text-faint">
          {{ t('chat.removedAgentHistory') }}
        </p>
        <footer v-else-if="chat.view !== 'list'" class="shrink-0 border-t border-line px-3 pt-2 pb-3">
          <p v-if="chat.error" class="mb-2 text-small text-danger">{{ chat.error }}</p>

          <!-- What goes with the message: the page, what is open there, what is selected. -->
          <p class="mb-2 flex flex-wrap items-center gap-1 text-small text-faint">
            <span class="rounded-pill bg-inset px-2 leading-5" :title="location.route">
              {{ pageLabelOnScreen(location.page) }}
            </span>
            <span
              v-if="placeChip"
              class="max-w-[10rem] truncate rounded-pill bg-inset px-2 leading-5"
              :title="placeChip.title"
            >
              {{ placeChip.label }}
            </span>
            <span
              v-if="chat.selection"
              class="flex max-w-[12rem] items-center gap-1 rounded-pill bg-accent-soft px-2 leading-5 text-primary"
              :title="chat.selection.slice(0, 400)"
            >
              <span class="truncate">
                {{ t('chat.context.selection', { count: chat.selection.length }) }}
              </span>
              <button
                class="grid size-4 shrink-0 place-items-center rounded-full hover:bg-hover"
                :title="t('chat.composer.withoutSelection')"
                @click="chat.clearSelection()"
              >
                <X class="size-3" />
              </button>
            </span>
          </p>

          <AttachmentPicker :key="attachmentDraftKey" v-model="attached" :disabled="chat.busy" @busy="uploadingAttachments = $event">
          <div class="flex items-end gap-2">
            <textarea
              ref="composer"
              v-model="draft"
              :rows="draftRows"
              class="min-h-9 flex-1 resize-none rounded-md border border-line bg-inset px-3 py-1.5 outline-none focus:border-accent"
              :placeholder="composerPlaceholder"
              @keydown="onComposerKeydown"
            />
            <button
              v-if="running && chat.activeChatId"
              class="grid size-9 shrink-0 place-items-center rounded-md border border-line hover:bg-hover"
              :title="t('chat.composer.stop')"
              @click="chat.stop(chat.activeChatId)"
            >
              <Square class="size-3.5" />
            </button>
            <button
              class="vxs-btn-primary grid size-9 shrink-0 place-items-center disabled:opacity-50"
              :disabled="!canSend"
              :title="running ? t('chat.composer.sendQueued', { name: producerName }) : t('chat.composer.send')"
              @click="submit"
            >
              <Send class="size-4" />
            </button>
          </div>
          </AttachmentPicker>
        </footer>
        </div>
        <!-- Full height beside the conversation, as in the task dialog; dropped below `lg`,
             where three columns would leave the transcript too narrow to read. -->
        <aside v-if="showStatus" class="w-[298px] shrink-0 border-l border-line max-lg:hidden">
          <SessionStatus :messages="chat.activeMessages" :subagents="chatSubagents" />
        </aside>
      </section>
      </Transition>

      <!-- The round button every website chat has: folds the popup out and back. The primary
           action's grey with a lit edge rather than an accent fill (`chat-launcher` below),
           its icon keeping the accent, since an icon is where colour goes. -->
      <button
        v-if="!docked"
        class="chat-launcher pointer-events-auto relative grid size-12 place-items-center rounded-full"
        :title="chat.open ? t('chat.minimiseChat') : t('chat.label')"
        :aria-expanded="chat.open"
        :aria-label="t('chat.label')"
        @click="chat.toggle()"
      >
        <ChevronDown v-if="chat.open" class="size-5" />
        <MessageCircle v-else class="size-5" />
        <!-- `vxs-pop`: the count lands on the button when a reply does. -->
        <span
          v-if="!chat.open && chat.unreadCount"
          class="vxs-pop absolute -top-0.5 -right-0.5 grid h-4 min-w-4 place-items-center rounded-pill bg-danger px-1 text-small font-medium text-on-accent"
        >
          {{ chat.unreadCount }}
        </span>
      </button>
    </div>
  </Teleport>
</template>

<style scoped>
.chat-launcher {
  background: var(--vxs-accent-gradient);
  color: var(--vxs-accent);
  box-shadow:
    inset 0 0 0 1px var(--vxs-border-strong),
    var(--vxs-shadow-lg);
}

/* The ground and the edge each step up, as the primary button's do (`base.css`). */
.chat-launcher:hover {
  background: var(--vxs-accent-gradient-hover);
  box-shadow:
    inset 0 0 0 1px color-mix(in oklab, var(--vxs-border-strong), var(--vxs-text-primary) 12%),
    var(--vxs-shadow-lg);
}
</style>
