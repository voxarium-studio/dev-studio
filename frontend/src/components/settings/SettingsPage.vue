<script setup lang="ts">
/** Shared layout and contents navigation for both settings scopes (spec §7). */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

import EmptyState from '@/components/ui/EmptyState.vue'
import { t, type MessageKey } from '@/i18n'

const props = defineProps<{
  titleKey: MessageKey
  subtitle: string
  sections: readonly { id: string; titleKey: MessageKey }[]
  available: boolean
  message?: string | null
}>()

const scroller = ref<HTMLElement | null>(null)
const pageEnd = ref<HTMLElement | null>(null)
const currentSection = ref(props.sections[0]?.id ?? '')

/**
 * Which section the reader is in: the first one crossing a band near the top of the page. An
 * observer rather than a scroll listener — it reports a section entering or leaving the band
 * and nothing in between. The last sections are short and can never reach that band, so the
 * end of the page is watched as well and, once on screen, names the last one.
 */
let sectionSpy: IntersectionObserver | null = null
let endSpy: IntersectionObserver | null = null
const inBand = new Set<string>()
let atEnd = false

function resolveSection() {
  if (atEnd) {
    currentSection.value = props.sections[props.sections.length - 1]?.id ?? ''
    return
  }
  const first = props.sections.find((section) => inBand.has(section.id))
  if (first) currentSection.value = first.id
}

function stopSpying() {
  sectionSpy?.disconnect()
  endSpy?.disconnect()
  sectionSpy = endSpy = null
  inBand.clear()
  atEnd = false
}

function spyOnSections() {
  stopSpying()
  const root = scroller.value
  if (!root) return
  sectionSpy = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) inBand.add(entry.target.id)
        else inBand.delete(entry.target.id)
      }
      resolveSection()
    },
    { root, rootMargin: '-8% 0px -62% 0px' },
  )
  for (const section of props.sections) {
    const element = document.getElementById(section.id)
    if (element) sectionSpy.observe(element)
  }
  if (!pageEnd.value) return
  endSpy = new IntersectionObserver(
    ([entry]) => {
      // A page short enough to show whole is at its end and its start at once; the start wins.
      atEnd = entry.isIntersecting && root.scrollTop > 0
      resolveSection()
    },
    { root },
  )
  endSpy.observe(pageEnd.value)
}

// The sections exist only once the config has arrived, which may be after the page mounts —
// hence both: an `immediate` watcher would run during setup, before there is a DOM to observe.
onMounted(() => {
  if (props.available) spyOnSections()
})
watch(
  () => props.available,
  (ready) => (ready ? spyOnSections() : stopSpying()),
  { flush: 'post' },
)
onBeforeUnmount(stopSpying)

function goToSection(id: string) {
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  document
    .getElementById(id)
    ?.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'start' })
}

</script>

<template>
  <div ref="scroller" class="h-full overflow-auto">
    <header class="vxs-pane border-b border-pane bg-surface px-pane py-2">
      <h1 class="text-title font-medium">{{ t(titleKey) }}</h1>
      <p class="truncate text-small text-faint" :title="subtitle">{{ subtitle }}</p>
    </header>
    <EmptyState v-if="!available" :title="t('settings.unavailable')" />
    <div v-else class="@container p-pane">
      <div class="flex items-start gap-10">
        <div class="max-w-2xl min-w-0 flex-1 space-y-6">
          <p v-if="message" role="status" class="rounded-md bg-inset p-2 text-muted">{{ message }}</p>
          <slot />
        </div>
        <nav
          class="sticky top-pane hidden w-44 shrink-0 @4xl:block"
          :aria-label="t('settings.onThisPage')"
        >
          <p class="mb-2 text-small tracking-wide text-faint uppercase">
            {{ t('settings.onThisPage') }}
          </p>
          <!-- One line down the list's edge, and the current section's stretch of it in the
               accent: the rail's mark, at the size of a page. -->
          <ul class="border-l border-line">
            <li v-for="section in sections" :key="section.id">
              <button
                type="button"
                class="-ml-px block w-full border-l py-1 pl-3 text-left"
                :class="
                  currentSection === section.id
                    ? 'border-accent text-primary'
                    : 'border-transparent text-muted hover:text-primary'
                "
                :aria-current="currentSection === section.id ? 'location' : undefined"
                @click="goToSection(section.id)"
              >
                {{ t(section.titleKey) }}
              </button>
            </li>
          </ul>
        </nav>
      </div>
      <div ref="pageEnd" class="h-px" aria-hidden="true" />
    </div>
  </div>
</template>
