<script setup lang="ts">
/**
 * A folded block of a transcript — thinking, or a run of tool calls (§10.4).
 *
 * Dashed rather than solid on purpose: the solid cards are someone speaking, and these are
 * the work around the speech. Collapsed at Default and open at Verbose in every transcript,
 * task and chat alike (the panel decides); the header is one button that toggles it, so the
 * whole line is the target. `lockedOpen` instead draws a noninteractive header and always
 * shows the content for a subagent launch (§10.4). `studio` is a run of the Studio's own
 * tools: the same fold with a violet tint and a violet dashed edge, the colour the Effort
 * Solver's card already wears.
 */
import { ChevronDown } from 'lucide-vue-next'

// §10.4: spawn blocks never fold, keeping Solver progress visible at both levels.
defineProps<{ open: boolean; label: string; studio?: boolean; lockedOpen?: boolean }>()
defineEmits<{ toggle: [] }>()
</script>

<template>
  <section
    class="rounded-md border border-dashed text-small text-muted"
    :class="studio ? 'border-violet-500/60 bg-violet-500/10' : 'border-line'"
    :data-studio-tools="studio ? '' : undefined"
    data-transcript-fold
  >
    <component
      :is="lockedOpen ? 'div' : 'button'"
      :type="lockedOpen ? undefined : 'button'"
      class="flex w-full min-w-0 items-center gap-2 rounded-md px-2 py-1 text-left hover:text-primary"
      :class="lockedOpen ? '' : studio ? 'hover:bg-violet-500/15' : 'hover:bg-hover'"
      :aria-expanded="lockedOpen ? undefined : open"
      @click="!lockedOpen && $emit('toggle')"
    >
      <slot name="icon" />
      <span class="shrink-0 font-medium">{{ label }}</span>
      <span class="min-w-0 flex-1 truncate text-faint"><slot name="summary" /></span>
      <span v-if="$slots.meta" class="shrink-0 text-faint"><slot name="meta" /></span>
      <ChevronDown v-if="!lockedOpen" class="vxs-chevron size-3.5 shrink-0" :class="open ? 'rotate-180' : ''" />
    </component>
    <div
      v-if="lockedOpen || open"
      class="border-t border-dashed px-3 py-2"
      :class="studio ? 'border-violet-500/40' : 'border-line'"
    >
      <slot />
    </div>
  </section>
</template>
