# VS-207 — Проверка типографики Studio

Проверено в Windows 11 без перезапуска или обновления рабочей Studio. Нативный тест рисует в памяти, Chrome получает файлы сборки через CDP, а проверка заставки открывает скрытое окно из checkout-версии Electron. Ни одна проверка не запускает бэкенд или меняет пользовательские документы.

## Настоящие глифы, а не только CSS-стек

| Поверхность | Проверка и результат |
| --- | --- |
| Нативный Kotlin/Compose + Jewel | Тест отрисовывает `Text` внутри рабочей `StudioTheme` на SVG-холст Skia и считывает физическое семейство и вес каждого глифа. В образцах «Voxarium Studio», «Студия Ёж», «Voxarium Студия Ёж» и `A∫Z` латиница отрисована локальным Allo Round 100, кириллица и `∫` — локальным Inter, а `☃` — Segoe UI Emoji. Для 400/500/600/700 глифы совпали с отдельными образцами нужного начертания. Проверены наклон контуров в курсиве и отдельный JetBrains Mono. Нативная заставка и Compose-диалоги используют ту же тему; отдельной Material-темы/зависимости в актуальном клиенте нет. |
| Нативные Swing-диалоги | AWT не принимает цепочку физических семей. Сборка дополняет таблицу символов статического Allo недостающими глифами Inter (400 и 700), не подменяя имеющиеся глифы, затем добавляет резерв `Dialog` через Swing `StyleContext`. Тест проверяет реальное имя regular/bold, дробный размер, смешанный текст и системный глиф вне обоих шрифтов. Шрифт регистрируется только в JVM; рамки ОС не меняются. |
| Vue и Electron shell | В реальной собранной оболочке с заглушкой IPC: латиница — Allo, кириллица — Inter, смешанная строка — 10 глифов Allo (с пробелами) + 8 глифов Inter. Значки `↗` и `×` сохранились; для SVG-иконки шрифт не нужен. |
| Редактор Vue/Tiptap | Тот же смешанный набор при 400, штатных 600 для `strong` и контрольных 700. В CDP видны, например, `AlloRound100-Bold-Round` и `InterVariable-Text-Bold`. `∂` отрисован Inter, `🚀` — Segoe UI Emoji после обоих локальных семейств. Курсив использует наклон Allo и локальный Inter Italic. Строчный и блочный код, а также моноширинная кнопка остались в Consolas при системной настройке моноширинного шрифта. |
| Встроенные страницы с собственными стилями | Проверены объявления шрифтов во встроенных Vue-плагинах: явные семейства относятся к моноширинным полям/коду/логам. В оригинальной странице Graphify проверено через CDP: строка статуса «Voxarium Студия Ёж» наследует Allo + Inter, тот же текст в поле пути отрисовывается Consolas из явного моноширинного override. |
| Заставка Electron | В Electron 45.1.0 / Chromium 156 проверены обе раскладки файлов: checkout и имитация пакета из фактических маппингов `desktop/package.json`. Стандартный заголовок 600 использует `AlloRound100-Display-SemiBold-Round`, смешанная строка статуса 400 — Allo + Inter, контрольный заголовок 700 — Display Bold обеих гарнитур, чистая кириллица 400 — только Inter. Запросы HTTP(S) заблокированы. |

Compose Desktop 1.12.1 не выражает упорядоченный резерв из двух произвольных семейств через публичный `FontFamily`. Поэтому цепочка задаётся в частном резолвере Compose/Skia, ограниченном темой Studio; на обновлении Compose контрактные тесты следует прогонять заново. Моноширинные семейства и SVG-ресурсы не заменяются.

## Проверка ROND и происхождения файлов

Источник: [Allo v1.0.3, официальный релиз](https://github.com/joshpuckett/allo/releases/tag/v1.0.3), `Allo-1.0.3.zip → fonts/variable/AlloVariable.ttf`. SHA-256 исходного TTF: `cdc994466d3a2dc77d5f72bd4f60c1bbf4d65dad5901ac064c7cc04e182c4fe4`. **Не** используются upstream static/Friendly (ROND=50). В WOFF2 фиксируется только `ROND=100`; `wght` 100–900 и `opsz` 9–32 остаются доступны Chromium. Нативные TTF фиксируют также `opsz=14` и нужный вес. OFL Allo и Inter включаются в соответствующие поставки.

`build-studio-fonts.py --verify` читает поставляемые файлы и отдельно инстанцирует контрольные образцы из официального вариативного файла. Сравниваются команды/координаты контуров и ширины глифов «Voxarium Studio», а для Swing также контуры Inter и объединённый cmap. Максимальное отклонение от ROND=100 на весах 400–700 составило 0,50 единицы из 2048 на em; контроль ROND=50 отличался на 65–129,34 единицы. Проверка не доверяет имени семейства или CSS `font-variation-settings`.

## Воспроизведение

Из корня checkout в PowerShell, с установленными зависимостями frontend/desktop, Chrome и JDK 25 для Gradle:

```powershell
$t = Join-Path $PWD '.voxarium_studio\temp\TASK-207'
New-Item -ItemType Directory -Force $t | Out-Null
$env:TEMP = $t
$env:TMP = $t
$env:NODE_COMPILE_CACHE = "$t\node-cache"

# Только для сверки с upstream (исполняемым клиентам сеть не нужна).
curl.exe -fsSL 'https://github.com/joshpuckett/allo/releases/download/v1.0.3/Allo-1.0.3.zip' -o "$t\Allo-1.0.3.zip"
Expand-Archive "$t\Allo-1.0.3.zip" "$t\upstream" -Force
python -m pip install --no-cache-dir --target "$t\python" fonttools==4.60.1 brotli==1.2.1
$env:PYTHONPATH = "$t\python"
python desktop/scripts/build-studio-fonts.py "$t\upstream\Allo-1.0.3\fonts\variable\AlloVariable.ttf" --verify

# Использует установленный Chrome или путь из CHROME_EXE; выводит 23 результата.
node desktop/scripts/verify-studio-typography.mjs "$t"

$electron = (Resolve-Path 'desktop/node_modules/electron/dist/electron.exe').Path
$splash = (Resolve-Path 'desktop/scripts/verify-splash-typography.cjs').Path
$p = Start-Process $electron -ArgumentList "`"$splash`" `"$t`"" -WindowStyle Hidden -Wait -PassThru `
  -RedirectStandardOutput "$t\splash.log" -RedirectStandardError "$t\splash-error.log"
Get-Content "$t\splash.log", "$t\splash-error.log"
if ($p.ExitCode) { throw "Splash probe failed: $($p.ExitCode)" }

# Нативная сборка и тесты, вывод Gradle направлен в временный каталог задачи.
$env:VS207_NATIVE_TEMP = $t
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=`"$t`""
'allprojects { layout.buildDirectory.set(file(System.getenv("VS207_NATIVE_TEMP") + "/native-out")) }' |
  Set-Content -Encoding ASCII "$t\typography.init.gradle"
.\desktop-native\gradlew.bat -p desktop-native --offline --project-cache-dir "$t\gradle-project" `
  -I "$t\typography.init.gradle" "-Pkotlin.project.persistent.dir=$t\kotlin" test jar

Push-Location frontend
foreach ($target in 'app', 'node', 'vitest') {
  .\node_modules\.bin\vue-tsc.cmd -p "tsconfig.$target.json" --tsBuildInfoFile "$t\$target.tsbuildinfo"
}
npm test -- --no-cache
Pop-Location
npm --prefix desktop test
```

Скрипт Chromium создаёт `web-render-results.json` с физическими именами и количеством глифов, а также снимки оболочки и редактора в указанном временном каталоге. Веб-рендеринг проверяется сборкой Vite, не запущенным сервером: CDP обслуживает только локальные файлы с двух тестовых origin и отклоняет внешние запросы.

Сборки Vite (SPA и тестовая страница) и Kotlin JAR прошли; в JAR проверено наличие TTF и лицензий. Прошли проверки типов app/node/vitest, 5794 теста Vitest (117 файлов), 368 тестов desktop и 101 тест Kotlin. NSIS-установщик и живая рабочая установка для этих проверок не использовались.
