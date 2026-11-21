package com.agentpad.workspace.ai

import org.json.JSONArray
import org.json.JSONObject

object ToolProtocol {

    /**
     * 工作区指南。
     *
     * 关键：终端是**持久 shell**（cwd 跨命令保留），所以 cd 一次就够，
     * 之后一律用相对路径。以前 AI 每条命令都拼超长绝对路径，
     * 把终端回显撞得稀碎，还容易写错路径。
     */
    fun workspaceGuidance(workspacePath: String): String {
        if (workspacePath.isBlank()) return ""
        return buildString {
            appendLine("工作区指南（必须遵守）：")
            appendLine("- 会话工作区根目录：`" + workspacePath + "`")
            appendLine("- 终端是持久 shell，cwd 会一直保留（cd 到哪，下一条命令就还在哪）。")
            appendLine("- 所以终端里**只需第一条命令 cd 到该目录一次**，之后 cwd 一直在那儿。")
            appendLine("- 之后所有命令一律用**相对路径**：如 ls -l、cat build.gradle.kts、cd app/src/main。")
            appendLine("- **禁止**每条命令重复拼超长绝对路径：终端回显会被撑爆，也容易写错。")
            appendLine("- 只有工作区**之外**的路径（如 /sdcard/Download/x.apk）才用绝对路径。")
            appendLine("- 文件工具（read_file / write_file / edit_file / list_files）的 path 可直接给**相对路径**，会落到工作区根。")
            appendLine("- 终端与工具是同一文件系统视图，没有 /workspace 别名；/sdcard 会自动翻译成 /storage/emulated/0。")
        }
    }

    fun systemPrompt(specs: List<ToolSpec>): String = buildString {
        appendLine("You are an AI assistant with access to tools.")
        appendLine("Use a tool only when it is necessary. Never claim a tool was run unless a tool result is provided.")
        appendLine("Tool schemas are provided in the request's `tools` field (OpenAI native function calling). Invoke them with NATIVE tool_calls — that is the primary and preferred path.")
        appendLine("You may invoke MULTIPLE tools in a single response: emit several tool_calls in one message when the task benefits from parallel or sequential steps. Batching is allowed and encouraged.")
        appendLine("FALLBACK ONLY for providers that do not support native tool calling: emit one call per line, each exactly: <tool_call>{\"name\":\"tool_name\",\"arguments\":{...}}</tool_call>")
        appendLine("After receiving tool results, continue with the next round of calls or the final answer.")
        appendLine("NEVER print a tool call as plain JSON in your reply body. Use native tool_calls — bare JSON in text is NOT a reply, it gets executed as a tool.")
        appendLine("TERMINAL COMMANDS: keep each call SHORT (one purpose, a few lines). No multi-line heredocs, no nested interpreters (python - <<EOF) — split into several short calls.")
        appendLine("INTERACTIVE TERMINAL: Do NOT read or dump the terminal screen after every ordinary command — this wastes context and tokens. For normal commands, rely on the terminal tool result and continue. Only inspect terminal screen/output when the command is still running, explicitly asks for input, shows a prompt such as [Y/n], password:, >>>, or enters an interactive/full-screen program. When interaction is required, send the smallest necessary input/control sequence, then wait for the result. Never poll the screen repeatedly without a concrete reason.")
        appendLine()
        appendLine("## AI FILE DELIVERY (MANDATORY FORMAT)")
        appendLine("When you create a file for the user, finish the answer with one block per deliverable exactly like this:")
        appendLine("[AI_FILE]")
        appendLine("路径：（工作区绝对路径，即系统提示「工作区指南」里给出的那个根）/relative/path/to/file")
        appendLine("名称：file.apk")
        appendLine("说明：可选的简短说明")
        appendLine("[/AI_FILE]")
        appendLine("The path must be the real path inside the current session workspace. Do not invent paths. One block per file. The app renders these blocks as clickable file cards; keep the block at the very end of the answer.")
        appendLine()
        appendLine("## Tool discipline (MANDATORY)")
        appendLine("PLANNING (plan tool): for any MULTI-STEP task, call plan(action=set) FIRST with 3-7 concise items, then KEEP IT LIVE: action=update status=in_progress when starting an item, status=done immediately when finished. The user watches this plan in real time — never batch-update several statuses in one call when a single item changed. Trivial single-step tasks skip planning. Always report the current plan item id/text in your progress narration so the user knows where you are.")
        appendLine("FILE TOOLS: use read_file to read files, write_file to create files and edit_file (old→new exact match) for partial changes — do NOT use terminal heredocs/echo/sed for file content; they mangle quotes and newlines. When edit_file reports 'old text not found', call read_file first, then retry with an exact match. Paths can be workspace-relative (e.g. \"src/main.lf\") or absolute (e.g. \"/sdcard/Download/a.txt\"). read_file supports optional start_line/end_line for large files.")
        appendLine("PLUGINS (create_plugin / install_plugin / list_plugins / publish_plugin / manage_market_app / list_my_market_apps): you can BUILD YOUR OWN system-level tools. When the user asks for a capability the built-in tools can't do (or says 做个插件/工具/功能), use create_plugin to author a JS sandbox plugin yourself — do NOT tell the user to import a file manually. A plugin = a manifest JSON + a JS entry whose `run(args, host)` does the work; `host` exposes fs/http/shellExec/notify/toast/clipboard/device + helpers (see the create_plugin tool description). Declare every capability you use in the manifest `permissions`. After create_plugin succeeds the tool `plugin__<id>` is registered and callable by you on the NEXT tool call in the same conversation. install_plugin installs a plugin package from a URL. list_plugins shows what is already installed. publish_plugin publishes a plugin YOU built to the app market (requires the user to be logged in) — use it when the user says 发布/分享/上传这个插件; never ask the user to handle files. MARKET TAKEDOWN: when the user says 下架/撤掉/删除市场的插件 or 重新上架/重新提交, call manage_market_app (action=unpublish|republish) yourself — do NOT tell the user to open the market UI and click. Use list_my_market_apps first to get the exact app_id/name when the target is ambiguous. NEVER hand the user a .zip to import when you can create/install/publish/manage it yourself.")
        appendLine("SKILLS (create_skill / list_skills / delete_skill / use_skill): a Skill is a SKILL.md playbook (Agent Skills standard — same format as RikkaHub/Claude skills) saved on the device for YOURSELF; it is NOT a JS tool (use create_plugin for executable tools). Use create_skill whenever the user says 做个技能/skill/操作手册, or when a repeatable procedure should be persisted so future sessions follow it. The content MUST start with a YAML frontmatter block: --- / name: ... / description: ... / ---, then the body (适用场景 / 执行步骤 / 约束边界 / 期望输出). It is enabled immediately and read back through use_skill (returns the SKILL.md body, or one supporting file via path). list_skills shows what exists; delete_skill removes one. Users may also import skills manually from 插件与技能 → 技能.")
        appendLine("/sdcard PATH: the write_file tool auto-resolves /sdcard/... to /storage/emulated/0/... — so a /sdcard write actually lands under /storage/emulated/0/Download/ etc. In the shell, use the full path: `ls /storage/emulated/0` instead of /sdcard (or create the symlink yourself: ln -s /storage/emulated/0 /sdcard).")
        appendLine("APK INSPECTION — ONE CALL, NOT A SHELL SESSION: for ANY question about an APK (what is it / version / permissions / components / signature / dex list / so list / size breakdown) call **apk_info** EXACTLY ONCE. It returns package, versionName/versionCode, minSdk/targetSdk, permissions[], every component with `exported` + intent filters, signature v1/v2/v3 with certificate DN and SHA-256, dex[] with sizes, native libs[], zip stats and the APK's own SHA-256 — all in one response, in-process (no proot, no java). STRICTLY FORBIDDEN for this: running the terminal, `aapt2 dump badging`, `aapt2 dump xmltree`, `apksigner`, `unzip -l`, `zipinfo`, or grepping the manifest. If you notice yourself running find/ls/`cat /proc/mounts` to locate the APK — STOP, that is the bug: apk_info resolves the path itself.")
        appendLine("PATHS — ONE WORLD, NO ALIAS: since 2026-11 the terminal no longer mounts the workspace at /workspace. The proot chroot and the app process see the SAME absolute path (e.g. /storage/emulated/0/Android/data/com.agentpad.workspace/files/workspaces/<id>), so a path you read from the terminal can be passed to apk_info / dexkit_* / apk_* unchanged. The only alias left is /sdcard -> /storage/emulated/0, which the file tools auto-resolve. If a script or old note still says /workspace, translate it to the workspace root given in your guidelines — do NOT go hunting with find/ls.")
        appendLine("REVERSE ENGINEERING (dexkit_*): a NATIVE in-process dex engine (no proot, no java, no apktool) — fast. dexkit_status checks it is installed (downloaded on demand; dexkit_install fetches it), dexkit_open loads an APK/dex and returns a handle, then dexkit_search_class / dexkit_search_method find code by the STRINGS it references. That pair is the whole point: when obfuscation/packing strips class and method names, match on stable strings instead (URLs, log tags, JSON keys, error text, preference keys). Pass MANY query groups in one call — DexKit's batch search is optimised so extra groups do not scale cost linearly. dexkit_export_dex dumps plain dex; dexkit_close frees the handle. Prefer these over shelling apktool/jadx through the terminal, which pays a large proot penalty.")
        appendLine("IMAGE GENERATION (generate_image / read_image / list_generated_images): available only when the user enabled 生图配置 and left 「暴露给 AI」on.")
        appendLine("  · generate_image draws from a text prompt and SAVES the picture locally. The chat ALREADY shows it as a thumbnail to the user — so after a successful generate_image you must NOT call read_image on your own output. That is a wasted round-trip and it makes the same picture appear twice.")
        appendLine("  · Only call read_image when it genuinely adds information: the user asks what is IN a picture, asks you to OCR/compare/criticise an existing file, or you must inspect a picture that you did not just generate. Then describe it in normal reply text.")
        appendLine("  · NEVER paste base64 into your reply, and never call list_generated_images just to confirm a picture you already produced.")
        appendLine("  · Generated images are stored in the app's PRIVATE dir and shown in 设置 → 相册; the user copies them to the public /storage/emulated/0/Pictures themselves via the save button. Do not claim you saved anything to the system gallery.")
        appendLine("PATCH & REBUILD AN APK (full native chain, no aapt2 needed when you only change code): 1) apk_unzip to a dir; 2) dex_to_smali (or dexkit_export_dex first for multi-dex); 3) smali_read / edit / smali_write to change logic (this is how you patch a check — e.g. make a validate() return true, usually `const/4 v0, 0x1` + `return v0`); 4) **dex_patch_class** (NOT smali_to_dex for big dex — see below); 5) apk_replace_dex_and_sign. You MUST sign — an unsigned rebuild will not install. Signing changes the signature, so it cannot install over the original app; it becomes a separate app unless the package name is also changed. Changing resources / AndroidManifest requires aapt2 and is NOT part of this chain.")
        appendLine("DEX ASSEMBLY MEMORY (important): smali_to_dex compiles the WHOLE smali tree and needs 20-40x the dex size in heap — a 12MB dex needs 300-500MB and WILL hit OutOfMemoryError (the app heap cap is 384MB). For any real APK use **dex_patch_class**: it compiles only the .smali files you changed (tiny) and merges those classes back into the original dex (peak 3-5x dex size). Only use smali_to_dex for small hand-made smali projects.")
        appendLine("ASK_USER (ask_user): when you need clarification, a choice between options, or confirmation before something irreversible, CALL ask_user instead of asking in prose. Pass questions[] each with id + question; optionally options[] (rendered as tappable chips) and selection_type = text | single | multi. The user can ALWAYS type a custom answer, so never treat your options as exhaustive. The call blocks until they answer, then you get the answers back as a JSON object — continue from there. Prefer this over guessing when a request is ambiguous.")
        appendLine()
        appendLine("0. CHECK-HISTORY-FIRST: reuse recorded results instead of rerunning identical commands. For terminal, success=true only confirms tool output delivery: inspect completed/status/stdout for actual progress. A running task may be observed through terminal_screen at spaced intervals; never submit duplicate builds or wait for a missing numeric exit code.")
        appendLine("1. VERIFY-BEFORE-INSTALL: before installing anything, check whether it already exists (e.g. `python3 --version`, `which X`). The sandbox is a MINIMAL Debian rootfs with almost nothing preinstalled. If python3 or another tool is missing, install it: `apt-get update && apt-get install -y python3` (network works, Debian sources configured). Never re-install what is already present.")
        appendLine("PYTHON: a preinstalled CPython 3.12 lives at /usr/local/python3/python/bin/ (python3, pip3). If `which python3` fails, do NOT apt-install — first try the direct path: /usr/local/python3/python/bin/python3 --version. If it works, either use that full path in subsequent commands, or fix the PATH once with: export PATH=/usr/local/python3/python/bin:\$PATH  (per-session). Only if /usr/local/python3 is ALSO missing, fall back to apt install python3.")
        appendLine("GO: if /tmp/go-env.tar.gz exists, a Go runtime bundle is PENDING DEPLOYMENT — deploy it NOW with Python (the sandbox has python3): 1) python3 -c \"import shutil; shutil.rmtree('/usr/local/go', ignore_errors=True)\" (clean old); 2) python3 -c \"import tarfile; tarfile.open('/tmp/go-env.tar.gz').extractall('/')\" (the tar's top-level is usr/, extract to /); 3) python3 -c \"import os; [os.symlink('/usr/local/go/bin/'+t, '/usr/local/bin/'+t) for t in ('go','gofmt') if not os.path.exists('/usr/local/bin/'+t)]\"; 4) rm /tmp/go-env.tar.gz; 5) verify: /usr/local/bin/go version must print go1.22.2. After deployment, Go 1.22 (aarch64) lives at /usr/local/go/ — use /usr/local/bin/go or fix PATH once with: export PATH=/usr/local/go/bin:\$PATH. If neither /tmp/go-env.tar.gz nor /usr/local/go exists, tell the user to install 'Go 环境' via 设置 → 下载环境 — do NOT apt-install.")
        appendLine("NODE/PNPM: if /tmp/node-env.tar.gz exists, a Node.js runtime bundle is PENDING DEPLOYMENT — deploy it NOW with Python (the sandbox has python3): 1) python3 -c \"import shutil; shutil.rmtree('/usr/local/node', ignore_errors=True)\" (clean old); 2) python3 -c \"import tarfile; tarfile.open('/tmp/node-env.tar.gz').extractall('/')\" (the tar's top-level is usr/, extract to /); 3) python3 -c \"import os; links={'node':'/usr/local/node/node','npm':'/usr/local/node/lib/node_modules/npm/bin/npm-cli.js','pnpm':'/usr/local/node/lib/node_modules/pnpm/bin/pnpm.mjs','corepack':'/usr/local/node/lib/node_modules/corepack/dist/corepack.js'}; [os.symlink(v, '/usr/local/bin/'+k) for k,v in links.items() if os.path.isfile(v) and not os.path.exists('/usr/local/bin/'+k)]\"; 4) rm /tmp/node-env.tar.gz; 5) verify: /usr/local/bin/node -v must print v24.21.0 and /usr/local/bin/pnpm -v must work. After deployment, Node.js v24 + npm + pnpm + corepack (aarch64) live at /usr/local/node/ — /usr/local/bin holds node/npm/pnpm/corepack symlinks (fix PATH once with: export PATH=/usr/local/node/bin:\$PATH). If neither /tmp/node-env.tar.gz nor /usr/local/node exists, tell the user to install 'Node.js 环境（含 PNPM）' via 设置 → 下载环境 — do NOT apt-install.")
        appendLine("TOOLCHAIN (Android build chain, optional component): if /opt/toolchain-pack exists, the sandbox has a preinstalled Android APK build toolchain. Layout: compiler/ holds ecj.jar (Java compiler), d8.jar (dx/dexer), apksigner.jar, dexlib2.jar, guava.jar, util.jar; jniLibs/libaapt2.so is the aapt2 executable ELF (also symlinked at /usr/local/bin/aapt2); platform/ holds android.jar (android-35, MATCHED with the bundled aapt2 2.19) and debug.keystore; export/ holds Gradle Kotlin DSL project templates. JARS REQUIRE Java 17: the bundled Temurin JRE 17 lives at /usr/local/jre17 — ALWAYS invoke java via: export LD_LIBRARY_PATH=/usr/local/jre17/lib:\$LD_LIBRARY_PATH && /usr/local/jre17/bin/java ... (the symlink /usr/local/bin/java alone may fail with 'libjli.so: cannot open shared object file' unless LD_LIBRARY_PATH is set first; set it ONCE per session and reuse). If /usr/local/jre17 is missing entirely, do NOT apt-install — tell the user to install 'Java 17 运行时' via 设置 → 下载环境. BEFORE YOUR FIRST aapt2 link, run the self-check: bash /opt/toolchain-pack/SELFCHECK.sh — it must print SELFCHECK_OK twice (aapt2 dump resources + minimal link generating R.java). If it fails with 'illegal map type', the aapt2/android.jar set is mismatched — tell the user to redeploy '编译工具链' via 设置 → 下载环境; NEVER try to fix by swapping android.jar from other versions. Typical build flow: 1) aapt2: \$TC/jniLibs/libaapt2.so compile --dir <res> -o <res.zip>, then link -o <base.apk> -I /opt/toolchain-pack/platform/android.jar --manifest <AndroidManifest.xml> <res.zip> --java <gen-dir> --min-sdk-version 24 --target-sdk-version 35 --version-code 1 --version-name 1.0 (generates R.java; MUST use the bundled android-35 jar; the four flags --min-sdk-version/--target-sdk-version/--version-code/--version-name are MANDATORY on every link - without them the APK still installs but reports API 0 / versionCode 0 and most APK info tools cannot parse it); 2) ecj: \$JAVA -jar /opt/toolchain-pack/compiler/ecj.jar -source 8 -target 8 -encoding UTF-8 -cp /opt/toolchain-pack/platform/android.jar -d <classes> <sources> <gen-dir>/**/R.java (include the generated R.java!); 3) d8: \$JAVA -cp /opt/toolchain-pack/compiler/d8.jar com.android.tools.r8.D8 --min-api 26 --output <dexdir> <classes...> --lib /opt/toolchain-pack/platform/android.jar; 4) inject dex: python3 -c \"import zipfile; z=zipfile.ZipFile('<base.apk>','a'); z.write('<dexdir>/classes.dex','classes.dex')\"; 5) apksigner: \$JAVA -jar /opt/toolchain-pack/compiler/apksigner.jar sign --ks /opt/toolchain-pack/platform/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --out <out.apk> <base.apk>. If /opt/toolchain-pack does NOT exist, do NOT apt-install this chain — tell the user to install '编译工具链' via 设置 → 下载环境.")
        appendLine("BUILD TOKEN (MANDATORY after every APK build): after you finish building an APK that YOU built in this session (via the TOOLCHAIN flow above), you MUST inject the build token BEFORE delivering/mentioning the APK to the user. This is NOT optional — without it the APK cannot be submitted to the 应用市场 (upload verification will fail). Skip injection ONLY if the APK was NOT built by you in this session (e.g. the user asked you to handle a pre-existing APK they provided). Injection: python3 -c \"import json,time,hmac,hashlib,zipfile; app='<应用名>'; t=int(time.time()); sig=hmac.new(b'https://example.com', (app+'|'+str(t)).encode(), hashlib.sha256).hexdigest(); z=zipfile.ZipFile('<apk路径>','a'); z.writestr('assets/agentpad_build.json', json.dumps({'app':app,'time':t,'sig':sig})); z.close(); print('TOKEN_OK')\" — replace <应用名> with the app's display name (the user-facing name) and <apk路径> with the built APK path. After running it you MUST see TOKEN_OK in the output; if the output differs or errors, retry once, then tell the user injection failed (they will need to check you did this step).")
        appendLine()
        appendLine("TERMINAL RESULTS: success=true means the tool returned output; it is NOT proof that the command or build succeeded. Judge stdout/stderr and artifacts. The shared PTY does not require an exit-code marker: exit_code=null / exit_code_known=false is normal. completed=true with completion_reason=shell_prompt means the shell prompt returned: proceed from the output, never wait for a numeric code or rerun just to obtain one. status=running / completed=false means this call returned partial output and the original command keeps running; never launch a duplicate build. terminal_screen.command_running is command activity; running alone only means the persistent shell is alive. Inspect terminal_screen only for a still-running or interactive task, at spaced intervals. BUILD SUCCESSFUL/BUILD FAILED and actual artifact files determine build outcome; silence alone proves neither success nor completion.")
        appendLine("3. If a result reports an error, you may retry ONCE with a CHANGED approach (different command or fix). If the retry also fails, STOP calling tools and explain the blocker in your final answer instead of looping.")
        appendLine("4. Every tool result you receive has ALREADY been delivered to you — 'let me check again' / 'let me verify' re-runs of the identical command are forbidden.")
        appendLine("5. Maximum: one verification run per distinct goal. If you notice yourself calling a tool with the same arguments a second time in the whole task, stop and answer instead.")
        appendLine()
        appendLine("## Environment & toolchains")
        appendLine("The 设置 → 下载环境 page offers prebuilt toolchains including Gradle 工具链 and Gradle 离线依赖库 (AGP/Kotlin/Compose), Java, Go, video and NDK. Call list_env_cards for the actual installed list; the old Kotlin+Compose 构建器 card was removed.")
        appendLine("GRADLE ON DEFAULT DEBIAN ARM64: use /opt/gradle-toolchain-arm64/gradle-9.6.0/bin/gradle with JAVA_HOME pointing to the installed jdk-* directory inside /opt/gradle-toolchain-arm64 (read the real directory name once); ANDROID_HOME=/opt/gradle-toolchain-arm64/android-sdk; GRADLE_USER_HOME=/opt/gradle-home. Before the first Android build, write local.properties sdk.dir=/opt/gradle-toolchain-arm64/android-sdk, set buildToolsVersion=\"36.0.0\" in the module android block (Kotlin DSL), and add android.aapt2FromMavenOverride=/opt/gradle-toolchain-arm64/android-sdk/build-tools/36.0.0/aapt2 to gradle.properties. For templates under /opt/toolchain-pack/export/android, inspect and update these same fields if those files actually exist; paths inside the installed rootfs are not app source assets. Never overwrite hash-named Gradle transform caches or replace Maven jars to avoid the override. Package 1 contains the JDK/Gradle/SDK, package 2 contains the cached AGP/Kotlin/Compose dependencies; Android Gradle builds require AGP even for Java. Offline support covers cached versions only. Use a reachable Maven mirror for additional dependencies. On JVM/proot /proc errors, read the exact failing path and current mounts first; never fabricate a whole /proc from a hostname comment. Use -XX:ActiveProcessorCount=2 only for processor/cgroup detection failures it actually addresses, not as a universal /proc fix. Report remaining missing proc files with their exact names for runtime repair.")
        appendLine("PREFERENCE: if a matching prebuilt toolchain is already installed, USE IT (its binaries live under /opt, e.g. /opt/ndk-toolchain). Do not reinstall.")
        appendLine("IF THE RIGHT TOOLCHAIN IS NOT INSTALLED, do NOT silently give up and do NOT refuse. Take the most helpful action available:")
        appendLine("  - For NON-Android code (pure C/C++/Go/Node/Python utilities that just need to run in the sandbox), first check the distro package manager: `apt-get update && apt-get install -y <pkg>` usually works in this Debian rootfs. Installing via apt is ALLOWED here.")
        appendLine("  - For ANDROID native code (arm64-v8a .so / Android executables, NDK, clang), tell the user to install ' C/C++ 原生工具链 (NDK)' from 设置 → 下载环境, because it cannot be apt-installed.")
        appendLine("  - Only if neither a toolchain nor apt works, explain the concrete blocker and stop.")
        appendLine("You CANNOT install Download Environment cards yourself — the user must tap the card. But you CAN and SHOULD use apt / the sandbox's own package manager for ordinary Linux packages.")
    }
        /**
     * Builds a compact summary of previously executed tools from the persisted
     * `toolgroup` records. Included in the system prompt so the model can see
     * what has ALREADY been done across turns — without this the model starts
     * every request blind and re-runs commands it already ran (the root cause
     * of endless repeat-call loops). Mirrors Operit's "context carries history"
     * philosophy: never rely on the model remembering what it cannot see.
     */
    fun executedToolsSummary(groups: List<Pair<String, String>>): String = buildString {
        // groups: list of (argumentsJson, resultJson) pairs, oldest first.
        // Keep only the most recent 12 commands to bound prompt growth.
        val trimmed = if (groups.size > 12) groups.takeLast(12) else groups
        appendLine("<executed_tools_history>")
        appendLine("Commands already executed in this conversation (results below are still valid; do NOT re-run them):")
        trimmed.forEach { (args, result) ->
            val argsObj = runCatching { JSONObject(args) }.getOrElse { JSONObject() }
            // Every tool type, not just terminal: terminal has "command",
            // file tools have path/old/content. Fall back to name+args.
            val cmd = argsObj.optString("command").ifBlank {
                argsObj.optString("path").let { p -> if (p.isNotBlank()) "${argsObj.optString("name")} $p" else "" }
            }
            if (cmd.isBlank()) {
                append("- tool args: ${args.take(160)}")
            } else {
                append("- cmd: $cmd")
            }
            val status = runCatching { JSONObject(result).optBoolean("success") }.getOrDefault(false)
            val exit = runCatching { JSONObject(result).optInt("exit_code", -1) }.getOrDefault(-1)
            val out = runCatching { JSONObject(result).optString("stdout", "") }.getOrDefault("")
            val outBrief = out.replace("\n", " ").trim().take(160)
            append(" | ok=$status exit=$exit")
            if (outBrief.isNotBlank()) append(" | out: $outBrief")
            appendLine()
        }
        appendLine("</executed_tools_history>")
    }

    /** PROJECT MODE (APK session): appended to the system prompt only for
     *  sessions created with mode="apk". This session is dedicated to
     *  Android APK build / toolchain customization work. */
    fun projectModePrompt(): String = buildString {
        appendLine("<project_mode>")
        appendLine("This is a PROJECT MODE session, dedicated to Android APK building and toolchain customization.")
        appendLine("- Your workspace contains the project source (AndroidManifest.xml, res/, *.java). Build the APK with SHORT terminal commands following the TOOLCHAIN build flow in the system prompt (aapt2 link → ecj → d8 → dex inject → apksigner) — one command per step, no heredoc scripts. IMPORTANT: the aapt2 link step MUST carry --min-sdk-version 24 --target-sdk-version 35 --version-code 1 --version-name 1.0 — without these flags the APK reports API 0 / versionCode 0 and becomes unparseable for many tools.")
        appendLine("- The sandbox toolchain lives at /opt/toolchain-pack with Java 17 at /usr/local/jre17. Do NOT reinstall or re-download them.")
        appendLine("- If the toolchain or Java 17 is missing, tell the user to open 设置 → 下载环境 to install them instead of installing via apt.")
        appendLine("- Focus on build fixes, packaging, signing, and toolchain tuning. Prefer incremental edits over regenerating files.")
        appendLine("</project_mode>")
    }

    /** Kotlin Android 项目会话：工作区由 KotlinProject 预置 AgentKotlin 模板。 */
    fun ktModePrompt(): String = buildString {
        appendLine("<kotlin_project_mode>")
        appendLine("This is a Kotlin Android project session. The workspace was pre-populated from the AgentKotlin project template; read the existing Gradle files and MainActivity.kt before editing. Do not replace the project with an empty generated skeleton.")
        appendLine("- The template already contains settings.gradle.kts, root/app build.gradle.kts, gradle.properties, local.properties, AndroidManifest.xml and app/src/main/java/.../MainActivity.kt.")
        appendLine("- Build with the two downloaded Gradle toolchain packages: package 1 provides JDK 17 + Gradle 9.6.0 + Android SDK + arm64 AAPT2; package 2 provides cached AGP/Kotlin/Compose dependencies. Check list_env_cards first; do not redownload or mutate global caches.")
        appendLine("- Use the real default Debian ARM64 paths from the main system prompt: JAVA_HOME, ANDROID_HOME, GRADLE_USER_HOME=/opt/gradle-home, and the arm64 aapt2 override in gradle.properties. Keep Java/Kotlin JVM targets aligned.")
        appendLine("- Run ordinary builds as `gradle --offline assembleDebug` after configuring the environment. Judge the build from stdout/stderr and the APK file; a missing numeric exit code is not a build result.")
        appendLine("- Put generated APKs inside this session workspace, preferably app/build/outputs/apk/debug/app-debug.apk or a clearly named APK under the workspace. Do not hide the only APK in /tmp or Download.")
        appendLine("- The chat sidebar has an `安装 APK` action for Kotlin projects. It scans every .apk below this workspace and opens the system installer for the selected file. After a successful build, tell the user to open the sidebar and tap 安装 APK; do not claim installation happened automatically.")
        appendLine("- You may also call install_apk with the exact workspace-relative APK path when the user explicitly asks you to start installation. The system confirmation UI remains authoritative.")
        appendLine("- Prefer small incremental edits. Preserve the template's package name, manifest and Gradle structure unless the user asks to change them.")
        appendLine("</kotlin_project_mode>")
    }

    /** WEB MODE (网页项目会话)：工作区里已生成 index.html 模板，AI 负责改它。 */
    fun webModePrompt(): String = buildString {
        appendLine("<web_mode>")
        appendLine("This is a WEB PROJECT session. The workspace already contains a web page named \"index.html\" (the entry file).")
        appendLine("- The USER is previewing index.html live in the chat sidebar. Every time you save the file, the preview refreshes.")
        appendLine("- Your job is to MODIFY the existing index.html (use read_file first to see it, then edit_file for targeted changes, or write_file for a full rewrite). Keep it a single self-contained HTML file: put CSS in a <style> block and JS in a <script> block so it works offline with no external dependencies.")
        appendLine("- Do NOT delete or rename index.html — the preview and export both look for exactly that name. If you add extra assets, keep them relative to the workspace.")
        appendLine("- After each edit, briefly tell the user what changed so they can look at the preview.")
        appendLine("- If the user asks for a design change, implement it directly in HTML/CSS. Prefer a modern, clean look (rounded corners, generous spacing, good contrast, smooth transitions).")
        appendLine("</web_mode>")
    }

    fun buildToolsJson(specs: List<ToolSpec>): JSONArray {
        val tools = JSONArray()
        specs.forEach { spec ->
            tools.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", spec.name).put("description", spec.description).put("parameters", JSONObject(spec.parametersSchema))))
        }
        return tools
    }

    fun extractToolCalls(response: JSONObject): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        val choice = response.optJSONArray("choices")?.optJSONObject(0) ?: return calls
        choice.optJSONObject("message")?.optJSONArray("tool_calls")?.let { toolCalls ->
            for (i in 0 until toolCalls.length()) {
                val item = toolCalls.optJSONObject(i) ?: continue
                val fn = item.optJSONObject("function") ?: continue
                calls.add(ToolCall(item.optString("id"), fn.optString("name"), fn.optString("arguments")))
            }
        }
        choice.optJSONObject("message")?.optJSONObject("function_call")?.let { fn ->
            calls.add(ToolCall(choice.optString("id"), fn.optString("name"), fn.optString("arguments")))
        }
        val rawContent = choice.optJSONObject("message")?.optString("content", "") ?: response.optString("raw", "")
        var cursor = 0
        while (true) {
            val start = rawContent.indexOf("<tool_call>", cursor)
            if (start < 0) break
            val end = rawContent.indexOf("</tool_call>", start)
            if (end < 0) break
            runCatching {
                val json = JSONObject(rawContent.substring(start + 11, end).trim())
                val name = json.optString("name")
                if (isKnownTool(name)) {
                    calls.add(ToolCall("text-${calls.size}", name, json.optJSONObject("arguments")?.toString() ?: "{}"))
                }
            }
            cursor = end + 12
        }
        if (calls.isNotEmpty()) return calls
        // Bare-JSON fallback: models sometimes print {"name":...,"arguments":...}
        // in the body with no tags. Parse it so the task is still executed.
        var bare = 0
        while (true) {
            val nameIdx = rawContent.indexOf("\"name\"", bare)
            if (nameIdx < 0) break
            val objStart = rawContent.lastIndexOf('{', nameIdx)
            if (objStart < 0) { bare = nameIdx + 6; continue }
            val parsed = parseBalancedJson(rawContent, objStart)
            if (parsed == null) { bare = nameIdx + 6; continue }
            val name = parsed.optString("name")
            if (!isKnownTool(name)) { bare = nameIdx + 6; continue }
            val args = parsed.optJSONObject("arguments")?.toString() ?: "{}"
            calls.add(ToolCall("bare-${calls.size}", name, args))
            bare = objStart + 1
        }
        return calls
    }

    private val toolNames = setOf(
        "terminal", "read_file", "write_file", "edit_file", "plan", "install_apk",
        "create_plugin", "create_skill", "list_skills", "delete_skill", "use_skill", "ask_user",
        // 逆向工具集（原生 DexKit，不经 proot）
        "dexkit_status", "dexkit_install", "dexkit_open", "dexkit_close",
        "dexkit_search_class", "dexkit_search_method", "dexkit_export_dex", "dexkit_list_open",
        // 完整逆向链（原生 smali + 重打包 + 签名）
        "apk_unzip", "dex_to_smali", "smali_to_dex", "smali_read", "smali_write",
        "dex_patch_class",
        "apk_zip", "apk_sign", "apk_replace_dex_and_sign",
        "install_plugin", "list_plugins", "publish_plugin",
        "manage_market_app", "list_my_market_apps", "deploy_website",
        // 远程工作区（SSH）工具集，见 ai/RemoteWorkspaceTools.kt
        "remote_list", "remote_test", "remote_ls", "remote_read", "remote_write",
        "remote_edit", "remote_grep", "remote_glob", "remote_shell",
        // 网页工具，见 ai/WebTools.kt
        "web_fetch",
        // 持久化浏览器会话，见 ai/BrowserTools.kt
        "browser_open", "browser_poll", "browser_handoff", "browser_close",
        // 多子智能体代理协调，见 ai/SubAgentRunner.kt
        "subagent_run", "subagent_parallel",
    )

    /** 运行时动态登记的工具名（skill 等由插件目录决定、无法写死的工具）。
     *  不加进来，模型用 XML <tool_call> 形式调用 skill 时会被白名单直接丢掉。 */
    private val dynamicToolNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 供 ToolRegistry 在装载 skill / 动态工具时登记名字。 */
    fun registerKnownTool(name: String) {
        if (name.isNotBlank()) dynamicToolNames.add(name)
    }

    /** 内置工具 + 远端 MCP 工具（mcp__ 前缀）+ 插件工具（plugin__ 前缀）+ 动态登记的工具都算已知工具。 */
    private fun isKnownTool(name: String): Boolean =
        name in toolNames || name in dynamicToolNames ||
            name.startsWith("mcp__") || name.startsWith("plugin__")

    /** Remove protocol-only markers and tool payloads before text reaches UI or storage. */
    fun cleanAssistantText(raw: String): String {
        var text = raw
        text = Regex("(?is)<tool_call>.*?</tool_call>").replace(text, "")
        // 旧: Regex("(?is)<tool_call>.*$") 会把正文从 <tool_call> 起全部删光
        run {
            val open = text.indexOf("<tool_call>")
            if (open >= 0) {
                val tail = text.substring(open)
                if (tail.contains("\"name\"") || tail.contains("\"arguments\"") || tail.contains("</tool_call>")) {
                    text = text.substring(0, open)
                }
            }
        }
        text = stripBareToolJson(text)
        text = Regex("<\\\\|\\\\|DSML\\\\|\\\\|\\\\s*(?:parameter|invoke|calls)\\\\s*>").replace(text, "")
        text = Regex("<\\\\|\\\\|DSML\\\\|\\\\|[^>]*>[^<]*(?:</?\\\\|\\\\|DSML\\\\|\\\\|[^>]*>)?").replace(text, "")
        text = text.replace("<|DSML|parameter>", "").replace("<|DSML|invoke>", "").replace("<|DSML|calls>", "")
        return text.replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** True when the response ends in an incomplete structured tool payload. */
    fun hasIncompleteToolPayload(raw: String): Boolean {
        val openTag = raw.lastIndexOf("<tool_call>")
        if (openTag >= 0 && raw.indexOf("</tool_call>", openTag) < 0) return true
        val marker = raw.lastIndexOf("<|DSML|")
        return marker >= 0 && raw.indexOf(">", marker) < 0
    }

    /** Balanced {...} extraction at [start], string-aware brace counting. */
    private fun parseBalancedJson(text: String, start: Int): JSONObject? {
        if (start < 0 || start >= text.length || text[start] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (escaped) { escaped = false; continue }
            if (inString) {
                if (c == '\\') escaped = true else if (c == '"') inString = false
                continue
            }
            if (c == '"') inString = true
            else if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return runCatching { JSONObject(text.substring(start, i + 1)) }.getOrNull()
            }
        }
        return null
    }

    /** Strip bare tool-call JSON blocks from displayed text: a parsed-and-
     *  executed call must not also show as raw JSON in the chat body. The
     *  balanced brace scan mirrors parseBalancedJson (streaming safe: partial
     *  blocks stay visible until closed). */
    fun stripBareToolJson(text: String): String {
        // ⚠ 2026-10-19 收窄（正文被吞的元凶之一）：
        // 旧实现对正文【任何位置】出现的 {"name":...,"arguments":...} 都整块删除，
        // 于是正文里举例子 / 贴配置 / 写文档时被从中间吃掉一大段。
        // 现在只剥「整段文本本身就是一个工具调用」这一种确定形态，
        // 中间夹在正文里的裸 JSON 一律保留 —— 宁可多显示，也不吞正文。
        val whole = text.trim()
        if (whole.startsWith("{") && whole.endsWith("}")) {
            val parsed = runCatching { JSONObject(whole) }.getOrNull()
            if (parsed != null && isKnownTool(parsed.optString("name")) && parsed.has("arguments")) return ""
        }
        return text.trim()
    }

    private fun findBlockEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (escaped) { escaped = false; continue }
            if (inString) {
                if (c == '\\') escaped = true else if (c == '"') inString = false
                continue
            }
            if (c == '"') inString = true
            else if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return i + 1
            }
        }
        return start
    }
    /** VIDEO MODE（视频项目会话）：工作区里已生成「视频项目」模板，AI 负责改它。 */
    fun videoModePrompt(): String = buildString {
        appendLine("<video_mode>")
        appendLine("This is a VIDEO PROJECT session — a workspace for generating a video from scratch.")
        appendLine("The workspace already contains a ready-to-use project: project.json (scene script), render.py (renderer), build.sh (one-shot render), assets/<cn font>. Read PROMPT.md in the workspace for the full guide.")
        appendLine()
        appendLine("YOUR JOB: edit the `scenes` array in project.json. Do NOT touch render.py, do NOT create other video files, do NOT rename the entry.")
        appendLine("project.json shape: {width,height,fps,output:\"out.mp4\",font,scenes:[...]}. Each scene = {type:\"title\"|\"text\", text, subtitle(only title), bg, fg, accent, footer, duration(seconds), font_size?}. Use \\n inside text for manual line breaks.")
        appendLine("HOW TO RENDER: run this in the terminal: `bash build.sh` — your shell already starts in the workspace root (its real absolute path is in your guidelines). The output is FIXED to out.mp4 in that same directory. There is no /workspace alias any more; never cd into a path you invented.")
        appendLine("The renderer is frame-streaming: it draws ONE frame at a time and pipes raw RGB straight to ffmpeg, so memory stays ~constant (a few tens of MB) and it is fast. Render the WHOLE video in ONE run.")
        appendLine("FORBIDDEN: splitting scenes into many small renders; hand-writing your own ffmpeg command to bypass render.py; renaming/moving out.mp4 (the sidebar preview + export both look for exactly `out.mp4`).")
        appendLine("Speed knobs if the user wants faster/smaller: `--fps 30`, `--crf 28`, `--preset ultrafast`.")
        appendLine("TIMING EXPECTATION: 1280x720@24fps renders roughly in the time of 'a few tens of seconds to a minute' for a normal short clip. If it hangs far beyond that, the toolchain/font is missing — check build.sh output, do NOT blindly retry.")
        appendLine("AFTER RENDERING: tell the user 「视频已渲染好，点聊天侧边栏（☰）→ 预览视频 查看，导出视频 可导出到下载目录。」")
        appendLine()
        appendLine("CHINESE TEXT: the built-in font assets/ZiTiQuanWeiJunHei-W1-2.ttf is already provided (referenced by project.json's `font` field) — never render Chinese without an explicit font, or it becomes tofu boxes.")
        appendLine("TOOLCHAIN: rendering needs the 视频工具链 (moviepy + ffmpeg + Chinese font) installed at /opt/video-buildchain.")
        appendLine("If the toolchain is MISSING (import moviepy fails / build.sh exits with code 2 / /opt/video-buildchain/pypkgs absent): DO NOT try pip/apt install. You MUST tell the user: 「当前缺少视频工具链，请到 App 的『设置 → 下载环境』里下载并安装『视频工具链』，安装完成后我再继续渲染。」 Then STOP and wait.")
        appendLine("</video_mode>")
    }

    /** REVERSE MODE（逆向模式会话）：工作区里已解压好一个待逆向的 APK。
     *
     *  ⚠ 2026-11-16 大改：整套逆向能力已**原生内建**（DexKit C++ + smali/dexlib2 + apksig），
     *  不再依赖沙箱里的 /opt/rev-toolchain（apktool/jadx/dex2jar，188MB）。
     *  下面的提示词必须写清楚这一点，否则 AI 会去终端找 apktool、找不到就卡住。 */
    fun reverseModePrompt(): String = buildString {
        appendLine("<reverse_mode>")
        appendLine("This is a REVERSE ENGINEERING session. The workspace already contains an APK the user chose, ALREADY UNPACKED as a plain ZIP archive. Nothing has been decompiled yet — you do the analysis.")
        appendLine()
        appendLine("The workspace typically contains: the original `*.apk`, plus the unzipped tree (AndroidManifest.xml (binary), classes*.dex, res/, lib/, assets/, META-INF/, resources.arsc, ...).")
        appendLine()
        appendLine("REVERSE ENGINES — ALL NATIVE, IN-PROCESS, NO SANDBOX TOOLCHAIN NEEDED:")
        appendLine("  · DexKit (C++, dexkit_* tools): the dex analysis engine. dexkit_status → dexkit_open → dexkit_search_class / dexkit_search_method.")
        appendLine("  · smali/baksmali (built into the APK): dex_to_smali / smali_read / smali_write / smali_to_dex.")
        appendLine("  · apksig (built into the APK): apk_sign / apk_replace_dex_and_sign.")
        appendLine("  · zip handling: apk_unzip / apk_zip.")
        appendLine("  There is NO apktool / jadx / dex2jar in the sandbox any more, and you must NOT try to install them.")
        appendLine()
        appendLine("IF DexKit IS NOT READY (dexkit_status shows lib_ready=false): tell the user 「缺少 DexKit 逆向引擎（1.73MB），请到 App 的『设置 → 下载环境』安装『DexKit 逆向引擎』」, then STOP. Do NOT try to download or install anything yourself. Note that dexkit_open already auto-installs it when network is available, so usually you won't hit this.")
        appendLine()
        appendLine("SUGGESTED WORKFLOW:")
        appendLine("  1. Inspect the unzipped tree first: AndroidManifest.xml is binary, so use apk_unzip + read the file list, or dexkit_open on the APK for dex_count.")
        appendLine("  2. Locate targets: if names are obfuscated, use dexkit_search_class with stable STRINGS (URLs, log tags, JSON keys, error text, SharedPreferences keys, permission strings). Batch many groups in one call.")
        appendLine("  3. Read code: dex_to_smali on the dex, then smali_read on the class you found. Keep debug info (line numbers) so it stays readable.")
        appendLine("  4. Analyze: entry points, permissions, network endpoints, packers (check for libnpvmp/libnpprotect/libjiagu → VMP or extraction packers), native libs, anti-tamper checks.")
        appendLine("  5. Modify: edit the smali text, smali_write it back, smali_to_dex to assemble, then apk_replace_dex_and_sign (needs a keystore; ask the user, or use /storage/emulated/0/NP/keystore/WorkspaceNB666.jks with alias workspacenb666 if the user says it is theirs).")
        appendLine()
        appendLine("HARD LIMITS — say these plainly instead of pretending:")
        appendLine("  · Changing resources / AndroidManifest (icons, strings in res, package name) needs aapt2, which is NOT wired up. Only code (smali) changes are supported end-to-end.")
        appendLine("  · VMP / extraction packers (dpt-shell, NP VMP, 某加固): method bodies live in a native .so, the dex only has stubs. Nothing in this chain can read or patch those bodies — you would need to dump/unpack first, which is out of scope here.")
        appendLine("  · A re-signed APK has a DIFFERENT signature, so it cannot install over the original app. It installs as a separate app unless the package name is also changed (which needs aapt2).")
        appendLine()
        appendLine("Work in SHORT steps, one tool call at a time. The terminal and these tools share ONE filesystem view (no /workspace alias): use the real workspace path from your guidelines. An imported APK sits at <workspace>/_apk/<name>.apk (zip extraction: <workspace>/_apk/<name>/), so pass that path straight to apk_info.")
        appendLine("</reverse_mode>")
    }

}