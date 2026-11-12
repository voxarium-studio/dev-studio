<script setup lang="ts">
/** The same chat list in the popup and the Chats page (§10.9). */
import { Plus, Trash2 } from 'lucide-vue-next'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import MemberMark from '@/components/teams/MemberMark.vue'
import { t } from '@/i18n'
import I18nT from '@/i18n/I18nT.vue'
import { formatTime } from '@/lib/status'
import { useChatStore } from '@/stores/chat'
import { useTeamsStore } from '@/stores/teams'

const chat = useChatStore()
const teams = useTeamsStore()
const agents = computed(() => teams.members.filter(member => member.kind === 'agent'))
const rowMember = (id: string) => teams.memberById.get(id) ?? null

// Follow FileContextMenu's single, body-teleported menu pattern (§10.9).
const menu = shallowRef<{ id: string; x: number; y: number; trigger: HTMLElement } | null>(null)
const menuRoot = ref<HTMLElement | null>(null)
const deleteButton = ref<HTMLButtonElement | null>(null)
const position = ref({ left: '0px', top: '0px' })

function closeMenu(restoreFocus = true) {
  const trigger = menu.value?.trigger
  menu.value = null
  if (restoreFocus && trigger?.isConnected) trigger.focus()
}

async function openMenu(event: MouseEvent | KeyboardEvent, id: string) {
  event.preventDefault()
  event.stopPropagation()
  const trigger = event.currentTarget as HTMLElement
  const rect = trigger.getBoundingClientRect()
  const keyboard = event instanceof KeyboardEvent || (event.clientX === 0 && event.clientY === 0)
  const current = { id, x: keyboard ? rect.left : event.clientX, y: keyboard ? rect.bottom : event.clientY, trigger }
  menu.value = current
  await nextTick()
  if (menu.value !== current || !menuRoot.value) return
  const bounds = menuRoot.value.getBoundingClientRect()
  // Measure rather than estimate so either language stays inside the viewport.
  position.value = {
    left: `${Math.max(8, Math.min(current.x, window.innerWidth - bounds.width - 8))}px`,
    top: `${Math.max(8, Math.min(current.y, window.innerHeight - bounds.height - 8))}px`,
  }
  deleteButton.value?.focus()
}

function onRowKeydown(event: KeyboardEvent, id: string) {
  if (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10')) void openMenu(event, id)
}

function onMenuKeydown(event: KeyboardEvent) {
  event.stopPropagation()
  if (event.key === 'Escape' || event.key === 'Tab') {
    if (event.key === 'Escape') event.preventDefault()
    closeMenu()
  } else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
    event.preventDefault()
    deleteButton.value?.focus()
  }
}

function removeChat() {
  const id = menu.value?.id
  closeMenu()
  if (id) void chat.remove(id)
}

function dismissMenu() { closeMenu() }
function onScroll(event: Event) {
  if (!(event.target instanceof Node) || !menuRoot.value?.contains(event.target)) closeMenu()
}
onMounted(() => {
  window.addEventListener('resize', dismissMenu)
  window.addEventListener('scroll', onScroll, true)
  window.addEventListener('blur', dismissMenu)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', dismissMenu)
  window.removeEventListener('scroll', onScroll, true)
  window.removeEventListener('blur', dismissMenu)
})
watch(() => chat.chats.map(entry => entry.id), ids => {
  if (menu.value && !ids.includes(menu.value.id)) closeMenu(false)
})
</script>

<template>
  <div class="min-h-0 flex-1 overflow-y-auto">
    <p v-if="chat.error && chat.view === 'list'" role="alert" class="px-3 py-2 text-small text-danger">{{ chat.error }}</p>
    <p v-if="chat.loading && !chat.loaded" class="p-4 text-faint">{{ t('common.loading') }}</p>
    <p v-else-if="!chat.chats.length && !agents.length && teams.members.length" class="p-4 text-faint">
      <I18nT keypath="chat.list.peopleOnly">
        <template #teams>
          <RouterLink to="/teams" class="text-accent hover:underline">
            {{ t('chat.page.teams') }}
          </RouterLink>
        </template>
      </I18nT>
    </p>
    <div v-else-if="!chat.chats.length" class="flex h-full flex-col items-center justify-center gap-3 p-4 text-center">
      <p class="max-w-[16rem] text-faint">{{ t('chat.list.empty') }}</p>
      <button
        v-if="agents.length"
        class="flex items-center gap-1.5 rounded-md border border-line px-3 py-1.5 hover:bg-hover"
        @click="chat.startNew()"
      >
        <Plus class="size-4" />
        <span>{{ t('chat.newChat') }}</span>
      </button>
    </div>
    <ul v-else class="py-1">
      <li v-for="entry in chat.chats" :key="entry.id">
        <button
          class="flex w-full items-center gap-2 px-3 py-2 text-left hover:bg-hover"
          :class="chat.view === 'chat' && chat.activeChatId === entry.id ? 'bg-accent-soft' : ''"
          :aria-current="chat.view === 'chat' && chat.activeChatId === entry.id ? 'true' : undefined"
          aria-haspopup="menu"
          :aria-expanded="menu?.id === entry.id"
          @contextmenu="openMenu($event, entry.id)"
          @keydown="onRowKeydown($event, entry.id)"
          @click="chat.openChat(entry.id)"
        >
          <MemberMark
            :member="rowMember(entry.member_id)"
            variant="badge"
            :corner="false"
            class="shrink-0"
          />
          <span class="min-w-0 flex-1 leading-tight">
            <span class="flex items-baseline gap-2">
              <span class="truncate font-medium">
                {{ rowMember(entry.member_id)?.name ?? entry.member_id }}
              </span>
              <span class="ml-auto shrink-0 text-small text-faint">
                {{ formatTime(entry.last_message_at) }}
              </span>
            </span>
            <span class="flex items-center gap-1.5 text-small text-muted">
              <span
                v-if="entry.is_running"
                class="vxs-dot-live size-1.5 shrink-0 rounded-full bg-accent"
                :title="t('chat.answering')"
              />
              <span
                v-else-if="chat.unread.has(entry.id)"
                class="size-1.5 shrink-0 rounded-full bg-accent"
                :title="t('chat.list.newReply')"
              />
              <span class="truncate">{{ entry.title || t('chat.newChat') }}</span>
              <span v-if="entry.producer_mode" class="shrink-0 rounded-pill bg-accent-soft px-1.5 text-accent">
                {{ t('chat.producer.label') }}
              </span>
            </span>
          </span>
        </button>
      </li>
    </ul>
  </div>

  <Teleport to="body">
    <template v-if="menu">
      <div
        class="fixed inset-0 z-50"
        @pointerdown="closeMenu()"
        @contextmenu.prevent="closeMenu()"
      />
      <div
        ref="menuRoot"
        role="menu"
        :aria-label="chat.chats.find(entry => entry.id === menu?.id)?.title || t('chat.newChat')"
        class="vxs-card fixed z-50 w-44 max-w-[calc(100vw-16px)] max-h-[calc(100vh-16px)] overflow-y-auto rounded-md border border-line bg-elevated py-1 shadow-lg"
        :style="position"
        @keydown="onMenuKeydown"
        @contextmenu.prevent
        @focusout="event => { if (!menuRoot?.contains(event.relatedTarget as Node)) closeMenu(false) }"
      >
        <button
          ref="deleteButton"
          type="button"
          role="menuitem"
          class="flex w-full items-center gap-2 rounded-sm px-2 py-1 text-left hover:bg-danger-soft focus-visible:bg-danger-soft"
          @click="removeChat"
        >
          <Trash2 class="size-4 text-danger" aria-hidden="true" />
          <span class="text-danger">{{ t('common.delete') }}</span>
        </button>
      </div>
    </template>
  </Teleport>
</template>
