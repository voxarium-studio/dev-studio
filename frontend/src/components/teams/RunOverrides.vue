<script setup lang="ts">
/** Per-task runtime choice and effort override (spec §10.3). */
import { onMounted } from 'vue'
import type { TeamMember } from '@/api/types'
import ModelSelector from '@/components/ui/ModelSelector.vue'
import ModelEffortSlider from '@/components/ui/ModelEffortSlider.vue'
import { t } from '@/i18n'
import { useTeamsStore } from '@/stores/teams'

defineProps<{
  backend: string | null
  model: string | null
  effort: string | null
  member: TeamMember | null
  disabled?: boolean
}>()
const emit = defineEmits<{
  'update:backend': [string | null]
  'update:model': [string | null]
  'update:effort': [string | null]
}>()
const teams = useTeamsStore()
onMounted(() => void teams.loadOptions())
function change(backend: string | null, model: string | null): void {
  emit('update:backend', backend)
  emit('update:model', model)
}
</script>

<template>
  <div v-if="!disabled" class="flex min-w-0 items-end gap-3">
    <div class="min-w-0 flex-1">
      <span class="mb-1 block text-small text-faint">{{ t('tasks.overrides.model') }}</span>
      <ModelSelector :backend="backend" :model="model" :options="teams.options"
        :inherited-backend="member?.agent_backend" :inherited-model="member?.model" allow-inherit @change="change" />
    </div>
    <label class="shrink-0">
      <span class="mb-1 block text-small text-faint">{{ t('tasks.overrides.effort') }}</span>
      <ModelEffortSlider :model-value="effort" :levels="teams.options?.effort_levels"
        :label="t('tasks.overrides.effort')" :default-label="t('modelEffort.inherit')"
        :default-title="member?.effort ? t('tasks.overrides.sameAsMemberValue', { value: member.effort }) : t('tasks.overrides.effortSolverAtStart')"
        @update:model-value="emit('update:effort', $event)" />
    </label>
  </div>
</template>
