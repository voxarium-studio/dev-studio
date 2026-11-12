/**
 * Graphify — the code graph the Studio keeps for the project, and when it is rebuilt.
 *
 * Plain ES module loaded at runtime: `vue`, the API client, `action`, `onEvent` and the
 * Studio's own `ModelSelector` come through the factory, and it is one file because the host appends `?token=` to this URL
 * (a relative import from here would resolve without it). Styling goes through the
 * `--vxs-*` properties rather than utility classes, which is what a plugin outside the
 * wheel would have to do.
 *
 * Everything on the page comes from the plugin's own backend through two actions —
 * `status` for the picture, `index` / `cancel` to change it — and the `plugin.event`
 * stream keeps the picture current while an extract runs.
 */

const PLUGIN_ID = 'graphify'

/**
 * The page's own words (§6.4), English first. A plugin cannot add keys to the Studio's locale
 * files, so they live here and `i18n.translator` follows the Studio's language. What the
 * backend says — reasons, log lines, hints, paths, commands — is shown as written.
 */
const MESSAGES = {
  en: {
    intro:
      'A knowledge graph of the code and the documents: the code from the local AST with no model or key, ' +
      'the documents read through the agent CLI you choose — Claude Code, Codex or Qwen Code. Search it from the rail ' +
      '(Ctrl+K); agents are told to ' +
      'query it before grepping — the wording is under Settings → Agent prompts → Code graph.',
    loading: 'Loading…',
    fields: {
      executable: {
        label: 'Executable',
        hint: 'The graphify command — a name on PATH or an absolute path',
      },
      path: {
        label: 'Scan path',
        hint: 'What to index, relative to the agent launch root',
      },
      indexOnOpen: {
        label: 'Index when the project opens',
        hint: 'Runs once per opening, in the background, after the Studio is up',
      },
      indexOnTaskDone: {
        label: 'Re-index when a task finishes',
        hint: 'Each move to Done rebuilds the graph, incrementally, so the next task sees the change',
      },
      documents: {
        label: 'Index the documents too',
        hint:
          'After the code, a second pass reads the Documents section (.voxarium_studio/docs) through the agent CLI chosen ' +
          'below (Claude Code, Codex or Qwen Code) on your own subscription into a graph of its own, merged with the code’s for agents and ' +
          'search. Pictures, PDFs, YAML and HTML are left out. Only changed documents are read; the rest come from the cache.',
      },
      documentsModel: {
        label: 'Documents backend and model',
        hint:
          'The agent CLI that reads them and its model. Claude Code defaults to haiku; Codex and Qwen Code use the model ' +
          'their CLI is configured with. Something else… takes any model id',
      },
      documentsLanguage: {
        label: 'Labels language',
        hint: 'What the model names things in — search finds a document by the words it is written in',
        source: 'as written — a Russian document gets Russian labels, an English one English',
        english: 'English — every label translated, the original kept in parentheses',
      },
    },
    index: {
      title: 'Index',
      notOnPath: '{executable} is not on PATH',
      install: 'Install it with {command}, or set the executable below.',
      launchRoot: 'Launch root',
      launchRootWhere: 'Settings → AI agents → Launch root',
      code: 'Code',
      codeHow: 'Local AST — no model, no key',
      documents: 'Documents',
      documentsWhat: 'The Documents section — {dir} — into a graph of its own, then merged with the code’s',
      documentsReadSource: 'Read through {backend} at {cli}, model {model}; labels in each document’s own language — {rule}',
      documentsReadEnglish: 'Read through {backend} at {cli}, model {model}; labels in English — {rule}',
      defaultModel: 'the CLI’s default model',
      ruleMemory: 'the rule is {memory}',
      ruleEndpoint: 'the rule is sent with every request through the Studio’s local endpoint',
      extraNeeded: 'graphify needs its openai extra: {command}',
      cliMissing: '{command} is not on PATH — {hint}. Documents are skipped until it is.',
      documentsOff: 'Not indexed — switch it on below.',
      graph: 'Graph',
      merged: 'Code and documents merged — what agents and the search field read',
      nodes: { one: '{count} node', other: '{count} nodes' },
      edges: { one: '{count} edge', other: '{count} edges' },
      communities: { one: '{count} community', other: '{count} communities' },
      // The node kinds the plugin's backend counts (`_node_kind`); anything else is `kindOther`.
      kinds: {
        class: { one: '{count} class', other: '{count} classes' },
        function: { one: '{count} function', other: '{count} functions' },
        symbol: { one: '{count} symbol', other: '{count} symbols' },
        note: { one: '{count} note', other: '{count} notes' },
        document: { one: '{count} document', other: '{count} documents' },
        paper: { one: '{count} paper', other: '{count} papers' },
        concept: { one: '{count} concept', other: '{count} concepts' },
      },
      kindOther: { one: '{count} {kind}', other: '{count} {kind}s' },
      written: 'Written {time}',
      writtenAtCommit: 'Written {time} · commit {commit}',
      notBuilt: 'Not built yet.',
      lastRun: 'Last run',
      running: 'Running since {time} — {reason}',
      failed: 'Failed {time}',
      finished: 'Finished {time} — {reason}',
      neverRan: 'Has not run yet in this session.',
      queued: 'One more run queued: {reason}',
      documentsRead: 'Documents: read and merged into the graph.',
      documentsSkipped: 'Documents: skipped — {reason}.',
      documentsDisabled: 'Documents: off.',
      buttonsHint:
        'Index now re-reads only what changed; Rebuild all re-reads every file — for a graph gone stale under a refactor.',
      indexNow: 'Index now',
      indexing: 'Indexing…',
      indexingPercent: 'Indexing… {percent}%',
      cancel: 'Cancel',
      rebuildAll: 'Rebuild all',
      refresh: 'Refresh',
      reset: 'Reset',
    },
    reset: {
      title: 'Reset the whole index?',
      body:
        'This removes {dir} entirely — the code graph, the documents graph, the merged graph, the manifests and ' +
        'every cache — and indexes everything again from scratch. Every document is read through the chosen agent CLI ' +
        'again, which costs time and your subscription’s quota. Index now and Rebuild all keep the caches; use those unless ' +
        'the graph is wrong in a way they do not fix.',
      bodyRunning:
        'This removes {dir} entirely — the code graph, the documents graph, the merged graph, the manifests and ' +
        'every cache — and indexes everything again from scratch. Every document is read through the chosen agent CLI ' +
        'again, which costs time and your subscription’s quota. The indexing running now is stopped first. Index now and ' +
        'Rebuild all keep the caches; use those unless the graph is wrong in a way they do not fix.',
      confirm: 'Yes, remove it all and start over',
      cancel: 'Cancel',
    },
    settings: {
      title: 'Settings',
      save: 'Save',
      saving: 'Saving…',
      savedAt: 'Saved at {time}',
    },
  },
  ru: {
    intro:
      'Граф знаний по коду и документам: код разбирается локально по AST — без модели и ключа, документы ' +
      'читаются через выбранный агентский CLI — Claude Code, Codex или Qwen Code. Искать по графу можно из боковой ' +
      'панели (Ctrl+K); агентам предписано ' +
      'обращаться к нему, прежде чем искать через grep, — формулировка в разделе Настройки → Промпты агентов → Граф кода.',
    loading: 'Загрузка…',
    fields: {
      executable: {
        label: 'Исполняемый файл',
        hint: 'Команда graphify — имя в PATH или абсолютный путь',
      },
      path: {
        label: 'Путь сканирования',
        hint: 'Что индексировать, относительно корня запуска агентов',
      },
      indexOnOpen: {
        label: 'Индексировать при открытии проекта',
        hint: 'Один раз при каждом открытии, в фоне, когда Studio уже запущена',
      },
      indexOnTaskDone: {
        label: 'Переиндексировать при завершении задачи',
        hint: 'Каждый переход в «Готово» инкрементально перестраивает граф, чтобы следующая задача видела изменения',
      },
      documents: {
        label: 'Индексировать и документы',
        hint:
          'После кода второй проход читает раздел «Документы» (.voxarium_studio/docs) через агентский CLI, выбранный ниже ' +
          '(Claude Code, Codex или Qwen Code), по вашей собственной подписке и строит из него отдельный граф, который объединяется с графом кода ' +
          'для агентов и поиска. Картинки, PDF, YAML и HTML пропускаются. Читаются только изменённые документы, ' +
          'остальные берутся из кэша.',
      },
      documentsModel: {
        label: 'Бэкенд и модель для документов',
        hint:
          'Агентский CLI, который их читает, и его модель. У Claude Code по умолчанию haiku; Codex и Qwen Code берут модель ' +
          'из настроек своего CLI. «Другая…» принимает любой идентификатор',
      },
      documentsLanguage: {
        label: 'Язык меток',
        hint: 'На каком языке модель называет сущности — поиск находит документ по словам того языка, на котором он написан',
        source: 'как в оригинале — у русского документа русские метки, у английского английские',
        english: 'английский — все метки переведены, оригинал сохранён в скобках',
      },
    },
    index: {
      title: 'Индекс',
      notOnPath: '{executable} не найден в PATH',
      install: 'Установите его командой {command} или укажите исполняемый файл ниже.',
      launchRoot: 'Корень запуска',
      launchRootWhere: 'Настройки → ИИ-агенты → Корень запуска',
      code: 'Код',
      codeHow: 'Локальный AST — без модели и ключа',
      documents: 'Документы',
      documentsWhat: 'Раздел «Документы» — {dir} — индексируется в отдельный граф, который затем объединяется с графом кода',
      documentsReadSource: 'Читаются через {backend} ({cli}), модель {model}; метки на языке самого документа — {rule}',
      documentsReadEnglish: 'Читаются через {backend} ({cli}), модель {model}; метки на английском — {rule}',
      defaultModel: 'по умолчанию из настроек CLI',
      ruleMemory: 'правило записано в {memory}',
      ruleEndpoint: 'правило передаётся с каждым запросом через локальный эндпоинт Studio',
      extraNeeded: 'graphify нужен дополнительный пакет openai: {command}',
      cliMissing: '{command} не найден в PATH — {hint}. Пока его нет, документы пропускаются.',
      documentsOff: 'Не индексируются — включите ниже.',
      graph: 'Граф',
      merged: 'Код и документы объединены — этот граф читают агенты и поиск',
      nodes: { one: '{count} узел', few: '{count} узла', many: '{count} узлов', other: '{count} узла' },
      edges: { one: '{count} связь', few: '{count} связи', many: '{count} связей', other: '{count} связи' },
      communities: {
        one: '{count} сообщество',
        few: '{count} сообщества',
        many: '{count} сообществ',
        other: '{count} сообщества',
      },
      kinds: {
        class: { one: '{count} класс', few: '{count} класса', many: '{count} классов', other: '{count} класса' },
        function: { one: '{count} функция', few: '{count} функции', many: '{count} функций', other: '{count} функции' },
        symbol: { one: '{count} символ', few: '{count} символа', many: '{count} символов', other: '{count} символа' },
        note: { one: '{count} заметка', few: '{count} заметки', many: '{count} заметок', other: '{count} заметки' },
        document: {
          one: '{count} документ',
          few: '{count} документа',
          many: '{count} документов',
          other: '{count} документа',
        },
        paper: { one: '{count} статья', few: '{count} статьи', many: '{count} статей', other: '{count} статьи' },
        concept: { one: '{count} понятие', few: '{count} понятия', many: '{count} понятий', other: '{count} понятия' },
      },
      kindOther: { other: '{kind}: {count}' },
      written: 'Записан {time}',
      writtenAtCommit: 'Записан {time} · коммит {commit}',
      notBuilt: 'Ещё не построен.',
      lastRun: 'Последний запуск',
      running: 'Выполняется с {time} — {reason}',
      failed: 'Завершился с ошибкой {time}',
      finished: 'Завершился {time} — {reason}',
      neverRan: 'В этой сессии запусков ещё не было.',
      queued: 'В очереди ещё один запуск: {reason}',
      documentsRead: 'Документы: прочитаны и объединены с графом.',
      documentsSkipped: 'Документы: пропущены — {reason}.',
      documentsDisabled: 'Документы: выключены.',
      buttonsHint:
        '«Индексировать» перечитывает только изменённое; «Перестроить всё» перечитывает все файлы — на случай, когда граф устарел после рефакторинга.',
      indexNow: 'Индексировать',
      indexing: 'Индексация…',
      indexingPercent: 'Индексация… {percent}%',
      cancel: 'Отменить',
      rebuildAll: 'Перестроить всё',
      refresh: 'Обновить',
      reset: 'Сбросить',
    },
    reset: {
      title: 'Сбросить весь индекс?',
      body:
        'Это полностью удалит {dir} — граф кода, граф документов, объединённый граф, манифесты и все кэши — и ' +
        'проиндексирует всё с нуля. Каждый документ снова будет прочитан через выбранный агентский CLI — это займёт время и ' +
        'израсходует квоту вашей подписки. «Индексировать» и «Перестроить всё» сохраняют кэши — пользуйтесь ими, ' +
        'если только граф не испорчен так, что они этого не исправят.',
      bodyRunning:
        'Это полностью удалит {dir} — граф кода, граф документов, объединённый граф, манифесты и все кэши — и ' +
        'проиндексирует всё с нуля. Каждый документ снова будет прочитан через выбранный агентский CLI — это займёт время и ' +
        'израсходует квоту вашей подписки. Идущая сейчас индексация сначала будет остановлена. «Индексировать» и ' +
        '«Перестроить всё» сохраняют кэши — пользуйтесь ими, если только граф не испорчен так, что они этого не исправят.',
      confirm: 'Да, удалить всё и начать заново',
      cancel: 'Отмена',
    },
    settings: {
      title: 'Настройки',
      save: 'Сохранить',
      saving: 'Сохранение…',
      savedAt: 'Сохранено в {time}',
    },
  },
}

// `label` and `hint` are message keys, resolved while rendering so they follow the language.
const FIELDS = [
  {
    key: 'executable',
    label: 'fields.executable.label',
    hint: 'fields.executable.hint',
    placeholder: 'graphify',
  },
  {
    key: 'path',
    label: 'fields.path.label',
    hint: 'fields.path.hint',
    placeholder: '.',
  },
]

const TOGGLES = [
  {
    key: 'index_on_open',
    label: 'fields.indexOnOpen.label',
    hint: 'fields.indexOnOpen.hint',
  },
  {
    key: 'index_on_task_done',
    label: 'fields.indexOnTaskDone.label',
    hint: 'fields.indexOnTaskDone.hint',
  },
  {
    key: 'documents',
    label: 'fields.documents.label',
    hint: 'fields.documents.hint',
  },
]

/**
 * The agent CLIs the documents pass can read through: Claude Code by graphify's own `claude-cli`
 * backend, Codex and Qwen Code by `--backend openai` against the local OpenAI-compatible endpoint
 * the Studio runs for the length of the pass. An empty `documents_backend` means Claude Code, so
 * a config saved before the choice existed keeps its `documents_model` as a Claude model.
 */
const DOCUMENT_BACKENDS = ['claude_code', 'codex', 'qwen']
const BACKEND_LABELS = { claude_code: 'Claude Code', codex: 'Codex', qwen: 'Qwen Code' }

const LANGUAGES = [
  { value: 'source', label: 'fields.documentsLanguage.source' },
  { value: 'english', label: 'fields.documentsLanguage.english' },
]

export default function ({ vue, api, action, onEvent, settings, i18n, components }) {
  const { defineComponent, h, computed, onMounted, onBeforeUnmount, ref } = vue
  const t = i18n.translator(MESSAGES)

  return defineComponent({
    name: 'GraphifySettingsPage',
    setup() {
      const values = ref({
        executable: '',
        path: '',
        index_on_open: true,
        index_on_task_done: true,
        documents: true,
        documents_backend: '',
        documents_model: '',
        documents_language: 'source',
      })
      const status = ref(null)
      /** `api.memberOptions()` — the picker's model list; null until it arrives, or if it fails. */
      const modelOptions = ref(null)
      const loading = ref(true)
      const saving = ref(false)
      /** When the last save landed — formatted while rendering, in the language on screen. */
      const savedAt = ref(null)
      const error = ref(null)
      const busy = ref(false)
      /** The reset's warning is showing; the second click is the one that resets. */
      const confirmingReset = ref(false)

      async function refresh() {
        try {
          status.value = await action('status')
          error.value = null
        } catch (cause) {
          error.value = cause instanceof Error ? cause.message : String(cause)
        }
      }

      let stop = null
      onMounted(async () => {
        try {
          const stored = await settings.get()
          values.value = { ...values.value, ...stored }
          await refresh()
        } catch (cause) {
          error.value = cause instanceof Error ? cause.message : String(cause)
        } finally {
          loading.value = false
        }
        // Without the list the picker still offers the default and a typed id — not worth an error.
        api.memberOptions().then(
          (options) => (modelOptions.value = options),
          () => {},
        )
        stop = onEvent('plugin.event', (payload) => {
          if (payload && payload.plugin_id === PLUGIN_ID && payload.name === 'index') {
            if (status.value) status.value = { ...status.value, indexer: payload.data }
            // The graph on disk changed when a run finished; the counts come from a re-read.
            if (payload.data && !payload.data.running) void refresh()
          }
        })
      })
      onBeforeUnmount(() => {
        if (stop) stop()
      })

      async function save() {
        saving.value = true
        error.value = null
        try {
          // `settings.save` replaces the whole dict, so an empty value left out is a value
          // cleared: no backend is Claude Code, no model is the backend's default.
          const payload = {}
          for (const [key, value] of Object.entries(values.value)) {
            if (typeof value === 'boolean') payload[key] = value
            else if (String(value ?? '').trim()) payload[key] = String(value).trim()
          }
          await settings.save(payload)
          savedAt.value = new Date()
          await refresh()
        } catch (cause) {
          error.value = cause instanceof Error ? cause.message : String(cause)
        } finally {
          saving.value = false
        }
      }

      async function invoke(name, payload) {
        busy.value = true
        try {
          const indexer = await action(name, payload)
          if (status.value) status.value = { ...status.value, indexer }
        } catch (cause) {
          error.value = cause instanceof Error ? cause.message : String(cause)
        } finally {
          busy.value = false
        }
      }

      const indexer = computed(() => status.value?.indexer ?? null)
      const graph = computed(() => status.value?.graph ?? null)
      const installed = computed(() => Boolean(status.value?.resolved))

      const when = (seconds) => (seconds ? new Date(seconds * 1000).toLocaleString(i18n.locale.value) : '')

      // ---- pieces --------------------------------------------------------------

      const card = (title, children) =>
        h(
          'section',
          {
            class: 'vxs-card',
            style: {
              maxWidth: '46rem',
              marginBottom: 'var(--vxs-space-5)',
              padding: 'var(--vxs-space-4)',
              borderRadius: 'var(--vxs-radius-lg)',
              border: '1px solid var(--vxs-card-border)',
              background: 'var(--vxs-bg-surface)',
              boxShadow: 'var(--vxs-card-shadow)',
            },
          },
          [h('h2', { style: { fontWeight: 500, marginBottom: 'var(--vxs-space-3)' } }, title), ...children],
        )

      const muted = (text, extra = {}) =>
        h('div', { style: { color: 'var(--vxs-text-muted)', ...extra } }, text)

      const faint = (text) =>
        h('div', { style: { fontSize: 'var(--vxs-text-small)', color: 'var(--vxs-text-faint)' } }, text)

      const mono = (text) =>
        h(
          'code',
          {
            style: {
              fontFamily: 'var(--vxs-font-mono)',
              fontSize: '12px',
              color: 'var(--vxs-text-primary)',
              wordBreak: 'break-all',
            },
          },
          text,
        )

      const row = (label, content) =>
        h('div', { style: { display: 'flex', gap: 'var(--vxs-space-4)', padding: '5px 0' } }, [
          h('div', { style: { width: '11rem', flexShrink: '0', color: 'var(--vxs-text-muted)' } }, label),
          h('div', { style: { minWidth: '0', flex: '1' } }, content),
        ])

      /**
       * `progress` (0–100, or null) turns the primary button into its own progress bar
       * while a run is on: a fill grows across it under the label. It is then inert but
       * not `disabled` — the app dims a disabled primary button, and a run in progress
       * is not a control that does nothing; `aria-busy` says what it is instead.
       *
       * `danger` is the destructive button — filled with the danger colour, the way the
       * app's own confirmations paint the one button that deletes.
       */
      const button = (
        label,
        onClick,
        { primary = false, danger = false, disabled = false, progress = null, style: extra = {} } = {},
      ) => {
        const filling = primary && progress !== null && progress !== undefined
        return h(
          'button',
          {
            class: primary ? 'vxs-btn-primary' : undefined,
            disabled: disabled && !filling,
            'aria-busy': filling ? 'true' : undefined,
            'aria-disabled': filling ? 'true' : undefined,
            onClick: filling ? undefined : onClick,
            style: primary
              ? {
                  position: 'relative',
                  overflow: 'hidden',
                  minWidth: '9.5rem',
                  padding: '6px 14px',
                  cursor: disabled || filling ? 'default' : 'pointer',
                  opacity: disabled && !filling ? 0.5 : 1,
                  ...extra,
                }
              : danger
                ? {
                    padding: '6px 12px',
                    borderRadius: 'var(--vxs-radius-md)',
                    border: '1px solid var(--vxs-danger)',
                    background: 'var(--vxs-danger)',
                    color: 'var(--vxs-white)',
                    fontWeight: 500,
                    cursor: disabled ? 'default' : 'pointer',
                    opacity: disabled ? 0.5 : 1,
                    ...extra,
                  }
                : {
                    padding: '6px 12px',
                    borderRadius: 'var(--vxs-radius-md)',
                    border: '1px solid var(--vxs-border)',
                    background: 'transparent',
                    color: 'var(--vxs-text-primary)',
                    cursor: disabled ? 'default' : 'pointer',
                    opacity: disabled ? 0.5 : 1,
                    ...extra,
                  },
          },
          filling
            ? [
                h('span', {
                  'aria-hidden': 'true',
                  style: {
                    position: 'absolute',
                    top: '0',
                    bottom: '0',
                    left: '0',
                    width: `${Math.max(0, Math.min(100, progress))}%`,
                    background: 'color-mix(in oklab, var(--vxs-text-on-accent) 30%, transparent)',
                    transition: 'width 300ms ease',
                    pointerEvents: 'none',
                  },
                }),
                h('span', { style: { position: 'relative' } }, label),
              ]
            : label,
        )
      }

      /** What the primary button says: the percent while a run is on, the verb otherwise. */
      const indexLabel = (run) => {
        if (!run || !run.running) return t('index.indexNow')
        const pct = run.progress ? run.progress.percent : null
        return pct === null || pct === undefined
          ? t('index.indexing')
          : t('index.indexingPercent', { percent: Math.round(pct) })
      }

      const input = (key, placeholder) =>
        h('input', {
          value: values.value[key] ?? '',
          placeholder,
          spellcheck: 'false',
          onInput: (event) => (values.value = { ...values.value, [key]: event.target.value }),
          style: {
            flex: '1',
            minWidth: '0',
            padding: '4px 8px',
            borderRadius: 'var(--vxs-radius-md)',
            border: '1px solid var(--vxs-border)',
            background: 'var(--vxs-bg-inset)',
            color: 'var(--vxs-text-primary)',
            fontFamily: 'var(--vxs-font-mono)',
            fontSize: '12px',
          },
        })

      const field = (spec) =>
        h('label', { style: { display: 'flex', gap: 'var(--vxs-space-4)', padding: '6px 0' } }, [
          h('div', { style: { width: '11rem', flexShrink: '0' } }, [h('div', t(spec.label)), faint(t(spec.hint))]),
          input(spec.key, spec.placeholder),
        ])

      const selectStyle = {
        flex: '1',
        minWidth: '0',
        padding: '4px 8px',
        borderRadius: 'var(--vxs-radius-md)',
        border: '1px solid var(--vxs-border)',
        background: 'var(--vxs-bg-inset)',
        color: 'var(--vxs-text-primary)',
      }

      /** The picker emits ('codex', '') on a backend switch, so a Claude model id is not carried over. */
      const chooseBackend = (backend, model) =>
        (values.value = { ...values.value, documents_backend: backend || 'claude_code', documents_model: model ?? '' })

      /** The documents backend and model, in the picker members, tasks and chats use; '' is the default. */
      const modelField = () =>
        h('div', { style: { display: 'flex', gap: 'var(--vxs-space-4)', alignItems: 'flex-start', padding: '6px 0' } }, [
          h('div', { style: { width: '11rem', flexShrink: '0' } }, [
            h('div', t('fields.documentsModel.label')),
            faint(t('fields.documentsModel.hint')),
          ]),
          // A Studio older than `runtime.components` (this file is served from disk, the SPA is
          // built) gets the plain id field rather than a page that fails to render.
          !components || !components.ModelSelector
            ? h('div', { style: { display: 'flex', gap: 'var(--vxs-space-2)', flex: '1', minWidth: '0' } }, [
                h(
                  'select',
                  {
                    value: values.value.documents_backend || 'claude_code',
                    disabled: saving.value,
                    onChange: (event) => chooseBackend(event.target.value, ''),
                    style: { ...selectStyle, flex: '0 0 auto' },
                  },
                  DOCUMENT_BACKENDS.map((id) => h('option', { value: id }, BACKEND_LABELS[id])),
                ),
                input('documents_model', (values.value.documents_backend || 'claude_code') === 'claude_code' ? 'haiku' : ''),
              ])
            : h(components.ModelSelector, {
                style: { flex: '1', minWidth: '0' },
                backend: values.value.documents_backend || 'claude_code',
                model: String(values.value.documents_model ?? ''),
                options: modelOptions.value,
                backends: DOCUMENT_BACKENDS,
                label: t('fields.documentsModel.label'),
                disabled: saving.value,
                onChange: chooseBackend,
              }),
        ])

      const toggle = (spec) =>
        h(
          'label',
          { style: { display: 'flex', gap: 'var(--vxs-space-3)', alignItems: 'flex-start', padding: '6px 0', cursor: 'pointer' } },
          [
            h('input', {
              type: 'checkbox',
              checked: values.value[spec.key] !== false,
              style: { marginTop: '3px' },
              onChange: (event) => (values.value = { ...values.value, [spec.key]: event.target.checked }),
            }),
            h('div', [h('div', t(spec.label)), faint(t(spec.hint))]),
          ],
        )

      /** One kind's count, e.g. "3 classes"; a kind this page has no words for keeps its own name. */
      const kindLabel = (kind, count) =>
        Object.prototype.hasOwnProperty.call(MESSAGES.en.index.kinds, kind)
          ? t(`index.kinds.${kind}`, { count })
          : t('index.kindOther', { count, kind })

      // ---- the status card ---------------------------------------------------

      const statusCard = () => {
        const s = status.value
        if (!s) return card(t('index.title'), [muted(t('loading'))])
        const g = graph.value
        const run = indexer.value
        const children = []

        children.push(
          row(
            'graphify',
            installed.value
              ? [mono(s.resolved)]
              : [
                  h('div', { style: { color: 'var(--vxs-danger)' } }, t('index.notOnPath', { executable: s.executable })),
                  faint(t('index.install', { command: s.install_hint })),
                ],
          ),
        )
        children.push(row(t('index.launchRoot'), [mono(s.launch_root), faint(t('index.launchRootWhere'))]))
        children.push(row(t('index.code'), [mono(s.command.join(' ')), faint(t('index.codeHow'))]))
        if (s.documents) {
          // An older backend reports Claude Code alone, under `claude` / `claude_hint`.
          const fresh = 'documents_cli' in s
          const cli = fresh ? s.documents_cli : s.claude
          const backendId = s.documents_backend || 'claude_code'
          children.push(
            row(t('index.documents'), [
              mono(s.documents_command.join(' ')),
              faint(t('index.documentsWhat', { dir: s.documents_dir })),
              cli
                ? faint(
                    t(s.documents_language === 'english' ? 'index.documentsReadEnglish' : 'index.documentsReadSource', {
                      backend: s.documents_backend_label || BACKEND_LABELS[backendId] || backendId,
                      cli,
                      model: s.documents_model || t('index.defaultModel'),
                      rule:
                        s.documents_endpoint || !s.documents_memory
                          ? t('index.ruleEndpoint')
                          : t('index.ruleMemory', { memory: s.documents_memory }),
                    }),
                  )
                : h(
                    'div',
                    { style: { color: 'var(--vxs-danger)' } },
                    t('index.cliMissing', {
                      command: (fresh && s.documents_cli_command) || 'claude',
                      hint: (fresh ? s.documents_cli_hint : s.claude_hint) || '',
                    }),
                  ),
              s.documents_extra_hint ? faint(t('index.extraNeeded', { command: s.documents_extra_hint })) : null,
            ]),
          )
        } else {
          children.push(row(t('index.documents'), [muted(t('index.documentsOff'))]))
        }

        if (g && g.exists) {
          const parts = []
          if (typeof g.nodes === 'number') {
            parts.push(
              [
                t('index.nodes', { count: g.nodes }),
                t('index.edges', { count: g.edges }),
                t('index.communities', { count: g.communities }),
              ].join(' · '),
            )
          }
          const kinds = g.kinds
            ? Object.entries(g.kinds)
                .filter(([, count]) => count)
                .map(([kind, count]) => kindLabel(kind, count))
                .join(', ')
            : ''
          children.push(
            row(t('index.graph'), [
              mono(g.path),
              g.merged ? faint(t('index.merged')) : null,
              parts.length ? h('div', { style: { marginTop: '2px' } }, parts.join(' ')) : null,
              kinds ? faint(kinds) : null,
              faint(
                g.built_at_commit
                  ? t('index.writtenAtCommit', { time: when(g.modified_at), commit: g.built_at_commit.slice(0, 7) })
                  : t('index.written', { time: when(g.modified_at) }),
              ),
            ]),
          )
        } else {
          children.push(row(t('index.graph'), [muted(t('index.notBuilt')), faint(g ? g.path : '')]))
        }

        if (run) {
          const state = run.running
            ? t('index.running', { time: when(run.started_at), reason: run.reason })
            : run.finished_at
              ? run.error
                ? t('index.failed', { time: when(run.finished_at) })
                : t('index.finished', { time: when(run.finished_at), reason: run.reason })
              : t('index.neverRan')
          const bits = [h('div', state)]
          if (run.running && run.progress && run.progress.stage) {
            bits.push(faint(run.progress.detail ? `${run.progress.stage} — ${run.progress.detail}` : run.progress.stage))
          }
          if (run.pending) bits.push(faint(t('index.queued', { reason: run.pending })))
          if (run.error) bits.push(h('div', { style: { color: 'var(--vxs-danger)' } }, run.error))
          const docs = run.documents
          if (docs && !run.running && run.finished_at) {
            if (docs.ran && !docs.error) bits.push(faint(t('index.documentsRead')))
            else if (docs.skipped) bits.push(faint(t('index.documentsSkipped', { reason: docs.skipped })))
            else if (!docs.enabled) bits.push(faint(t('index.documentsDisabled')))
          }
          if (run.log && run.log.length) {
            bits.push(
              h(
                'pre',
                {
                  style: {
                    marginTop: 'var(--vxs-space-2)',
                    maxHeight: '14rem',
                    overflow: 'auto',
                    padding: '8px 10px',
                    borderRadius: 'var(--vxs-radius-md)',
                    background: 'var(--vxs-bg-inset)',
                    fontFamily: 'var(--vxs-font-mono)',
                    fontSize: '11px',
                    lineHeight: '1.5',
                    whiteSpace: 'pre-wrap',
                    color: 'var(--vxs-text-muted)',
                  },
                },
                run.log.join('\n'),
              ),
            )
          }
          children.push(row(t('index.lastRun'), bits))
        }

        children.push(faint(t('index.buttonsHint')))
        children.push(
          h('div', { style: { display: 'flex', gap: 'var(--vxs-space-3)', marginTop: 'var(--vxs-space-3)' } }, [
            button(indexLabel(run), () => invoke('index'), {
              primary: true,
              disabled: busy.value || Boolean(run && run.running),
              progress: run && run.running && run.progress ? run.progress.percent : null,
            }),
            run && run.running
              ? button(t('index.cancel'), () => invoke('cancel'), { disabled: busy.value })
              : button(t('index.rebuildAll'), () => invoke('index', { force: true }), {
                  disabled: busy.value,
                }),
            button(t('index.refresh'), refresh, { disabled: busy.value }),
            // Apart from the others, at the far right: the one button here that destroys.
            button(t('index.reset'), () => (confirmingReset.value = true), {
              danger: true,
              disabled: busy.value || confirmingReset.value,
              style: { marginLeft: 'auto' },
            }),
          ]),
        )
        if (confirmingReset.value) {
          children.push(
            h(
              'div',
              {
                role: 'alertdialog',
                style: {
                  marginTop: 'var(--vxs-space-3)',
                  padding: 'var(--vxs-space-3) var(--vxs-space-4)',
                  borderRadius: 'var(--vxs-radius-md)',
                  border: '1px solid var(--vxs-danger)',
                  background: 'var(--vxs-danger-soft)',
                },
              },
              [
                h('div', { style: { fontWeight: 500, color: 'var(--vxs-danger)' } }, t('reset.title')),
                h(
                  'p',
                  { style: { marginTop: '4px', color: 'var(--vxs-text-primary)' } },
                  t(run && run.running ? 'reset.bodyRunning' : 'reset.body', {
                    dir: `${s.launch_root}\\graphify-out`,
                  }),
                ),
                h('div', { style: { display: 'flex', gap: 'var(--vxs-space-3)', marginTop: 'var(--vxs-space-3)' } }, [
                  button(
                    t('reset.confirm'),
                    async () => {
                      confirmingReset.value = false
                      await invoke('reset')
                    },
                    { danger: true, disabled: busy.value },
                  ),
                  button(t('reset.cancel'), () => (confirmingReset.value = false), { disabled: busy.value }),
                ]),
              ],
            ),
          )
        }
        return card(t('index.title'), children)
      }

      // ---- the page ------------------------------------------------------------

      return () =>
        h('div', { style: { height: '100%', overflow: 'auto', padding: 'var(--vxs-pane-pad)' } }, [
          h('header', { style: { marginBottom: 'var(--vxs-space-5)' } }, [
            h('h1', { style: { fontSize: 'var(--vxs-title-size)', fontWeight: 600 } }, 'Graphify'),
            h('p', { style: { color: 'var(--vxs-text-muted)' } }, t('intro')),
          ]),

          error.value
            ? h(
                'p',
                {
                  style: {
                    maxWidth: '46rem',
                    marginBottom: 'var(--vxs-space-4)',
                    padding: '8px 12px',
                    borderRadius: 'var(--vxs-radius-md)',
                    border: '1px solid var(--vxs-border)',
                    color: 'var(--vxs-danger)',
                  },
                },
                error.value,
              )
            : null,

          loading.value ? h('p', { style: { color: 'var(--vxs-text-muted)' } }, t('loading')) : statusCard(),

          loading.value
            ? null
            : card(t('settings.title'), [
                ...FIELDS.map(field),
                ...TOGGLES.map(toggle),
                values.value.documents !== false ? modelField() : null,
                values.value.documents !== false
                  ? h('label', { style: { display: 'flex', gap: 'var(--vxs-space-4)', padding: '6px 0' } }, [
                      h('div', { style: { width: '11rem', flexShrink: '0' } }, [
                        h('div', t('fields.documentsLanguage.label')),
                        faint(t('fields.documentsLanguage.hint')),
                      ]),
                      h(
                        'select',
                        {
                          value: values.value.documents_language || 'source',
                          onChange: (event) =>
                            (values.value = { ...values.value, documents_language: event.target.value }),
                          style: selectStyle,
                        },
                        LANGUAGES.map((option) => h('option', { value: option.value }, t(option.label))),
                      ),
                    ])
                  : null,
                h('div', { style: { display: 'flex', alignItems: 'center', gap: 'var(--vxs-space-3)', marginTop: 'var(--vxs-space-3)' } }, [
                  button(saving.value ? t('settings.saving') : t('settings.save'), save, {
                    primary: true,
                    disabled: saving.value,
                  }),
                  savedAt.value
                    ? faint(t('settings.savedAt', { time: savedAt.value.toLocaleTimeString(i18n.locale.value) }))
                    : null,
                ]),
              ]),
        ])
    },
  })
}
