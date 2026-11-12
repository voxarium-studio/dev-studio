/**
 * Folded or open for one section of the task's Status column (§10.4), remembered in the
 * browser like the transcript's verbosity dial: the preference is the reader's rather than the
 * task's. Folded is the default, so a first read starts with every section's heading and count
 * in view and opens only the lists it wants.
 *
 * No storage — or no `window` at all, rendering on the server — means folded and unremembered.
 */
import { ref, watch, type Ref } from 'vue'

export function rememberedFold(key: string): Ref<boolean> {
  const storageKey = `voxarium_studio.status.${key}`
  let initial = false
  try {
    initial = window.localStorage.getItem(storageKey) === 'open'
  } catch {
    // Folded.
  }
  const open = ref(initial)
  watch(open, (value) => {
    try {
      window.localStorage.setItem(storageKey, value ? 'open' : 'closed')
    } catch {
      // Unremembered, it still folds.
    }
  })
  return open
}
